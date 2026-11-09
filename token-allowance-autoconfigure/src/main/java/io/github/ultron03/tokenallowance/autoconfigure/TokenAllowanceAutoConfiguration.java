package io.github.ultron03.tokenallowance.autoconfigure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.ClassUtils;

import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.Pricing;
import io.github.ultron03.tokenallowance.core.BudgetListener;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.ConfiguredPriceCatalog;
import io.github.ultron03.tokenallowance.core.ConservativeTokenEstimator;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.LimitResolver;
import io.github.ultron03.tokenallowance.core.PriceCatalog;
import io.github.ultron03.tokenallowance.core.TokenEstimator;
import io.github.ultron03.tokenallowance.core.UsageLedger;
import io.github.ultron03.tokenallowance.redis.RedisUsageLedger;
import io.github.ultron03.tokenallowance.springai.advisor.AdmissionAdvisor;
import io.github.ultron03.tokenallowance.springai.actuator.TokenAllowanceEndpoint;
import io.github.ultron03.tokenallowance.springai.advisor.MeteringAdvisor;
import io.github.ultron03.tokenallowance.springai.event.ApplicationEventBudgetListener;
import io.github.ultron03.tokenallowance.springai.metrics.MicrometerBudgetListener;
import io.github.ultron03.tokenallowance.springai.subject.AdvisorParamSubjectResolver;
import io.github.ultron03.tokenallowance.springai.subject.CompositeSubjectResolver;
import io.github.ultron03.tokenallowance.springai.subject.HeaderSubjectResolver;
import io.github.ultron03.tokenallowance.springai.subject.SpringSecuritySubjectResolver;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;
import io.github.ultron03.tokenallowance.springai.web.BudgetExceededExceptionHandler;

/**
 * Builds the budget service from {@code token-allowance.*} and adds the admission and metering
 * advisors to every {@link ChatClient.Builder} that Spring AI creates.
 *
 * <p>Each bean steps aside when the app defines its own of the same type; the builder customizer
 * steps aside for a bean named {@value #CUSTOMIZER_BEAN_NAME}. A {@code ChatClient} built by hand
 * with {@code ChatClient.builder(..)} gets no advisors; add the two advisor beans to it yourself.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBooleanProperty(prefix = TokenAllowanceProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(TokenAllowanceProperties.class)
public class TokenAllowanceAutoConfiguration {

	/** Name of the builder customizer bean; define a bean with this name to replace it. */
	public static final String CUSTOMIZER_BEAN_NAME = "tokenAllowanceChatClientBuilderCustomizer";

	private static final String SPRING_SECURITY_CLASS = "org.springframework.security.core.context.SecurityContextHolder";

	private static final String REQUEST_CONTEXT_CLASS = "org.springframework.web.context.request.RequestContextHolder";

	private static final String SERVLET_REQUEST_CLASS = "jakarta.servlet.http.HttpServletRequest";

	private static final String STORE_PREFIX = TokenAllowanceProperties.PREFIX + ".store";

	/** The clock that tells which window a call falls in. */
	@Bean
	@ConditionalOnMissingBean
	Clock tokenAllowanceClock() {
		return Clock.systemUTC();
	}

	/** Estimates prompt tokens before a call. */
	@Bean
	@ConditionalOnMissingBean
	TokenEstimator tokenEstimator() {
		return new ConservativeTokenEstimator();
	}

	/** Prices from {@code token-allowance.pricing}. */
	@Bean
	@ConditionalOnMissingBean
	PriceCatalog priceCatalog(TokenAllowanceProperties properties) {
		Pricing pricing = properties.pricing();
		return new ConfiguredPriceCatalog(PropertyMapping.prices(pricing.models()), pricing.unknownModel(),
				PropertyMapping.defaultPrice(pricing));
	}

	/** Spend kept in memory, so per instance and lost on restart. */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = STORE_PREFIX, name = "type", havingValue = "in-memory", matchIfMissing = true)
	UsageLedger usageLedger(Clock clock, TokenAllowanceProperties properties) {
		return new InMemoryUsageLedger(clock, properties.zone());
	}

	/** Limits from {@code token-allowance.limits} and {@code token-allowance.plans}. */
	@Bean
	@ConditionalOnMissingBean
	LimitResolver limitResolver(TokenAllowanceProperties properties) {
		return PropertyMapping.planLimits(properties.limits(), properties.plans());
	}

	/** Applies each caller's limits and the per-request caps, and tells every {@link BudgetListener} bean. */
	@Bean
	@ConditionalOnMissingBean
	BudgetService budgetService(UsageLedger ledger, PriceCatalog priceCatalog, LimitResolver limitResolver,
			Clock clock, TokenAllowanceProperties properties, ObjectProvider<BudgetListener> listeners) {
		return new BudgetService(ledger, priceCatalog, limitResolver,
				PropertyMapping.requestLimit(properties.perRequest()), properties.mode(),
				PropertyMapping.failureMode(properties.store().failureMode(), properties.mode()), clock,
				properties.zone(), properties.thresholds(), listeners.orderedStream().toList());
	}

	/**
	 * Finds the subject from the advisor param, then Spring Security, then the HTTP header. Security
	 * and the header are only asked when their classes are present.
	 */
	@Bean
	@ConditionalOnMissingBean
	SubjectResolver subjectResolver(TokenAllowanceProperties properties, ResourceLoader resourceLoader) {
		@Nullable ClassLoader classLoader = resourceLoader.getClassLoader();
		List<SubjectResolver> resolvers = new ArrayList<>();
		resolvers.add(new AdvisorParamSubjectResolver());
		if (ClassUtils.isPresent(SPRING_SECURITY_CLASS, classLoader)) {
			resolvers.add(new SpringSecuritySubjectResolver());
		}
		if (ClassUtils.isPresent(REQUEST_CONTEXT_CLASS, classLoader)
				&& ClassUtils.isPresent(SERVLET_REQUEST_CLASS, classLoader)) {
			resolvers.add(new HeaderSubjectResolver(properties.subject().header()));
		}
		return new CompositeSubjectResolver(resolvers);
	}

	/** Admits or denies each request once, before any tool loop. */
	@Bean
	@ConditionalOnMissingBean
	AdmissionAdvisor admissionAdvisor(SubjectResolver subjectResolver, BudgetService budgetService) {
		return new AdmissionAdvisor(subjectResolver, budgetService);
	}

	/** Reserves and charges every model call, including each round of a tool loop. */
	@Bean
	@ConditionalOnMissingBean
	MeteringAdvisor meteringAdvisor(BudgetService budgetService, TokenEstimator tokenEstimator,
			TokenAllowanceProperties properties) {
		return new MeteringAdvisor(budgetService, tokenEstimator, properties.stream().interrupted());
	}

	/** Adds both advisors to each builder; their order comes from the advisors themselves. */
	@Bean(CUSTOMIZER_BEAN_NAME)
	@ConditionalOnMissingBean(name = CUSTOMIZER_BEAN_NAME)
	ChatClientBuilderCustomizer tokenAllowanceChatClientBuilderCustomizer(AdmissionAdvisor admissionAdvisor,
			MeteringAdvisor meteringAdvisor) {
		return builder -> builder.defaultAdvisors(admissionAdvisor, meteringAdvisor);
	}

	/** Publishes threshold events to the application context. */
	@Bean
	@ConditionalOnMissingBean
	ApplicationEventBudgetListener applicationEventBudgetListener(ApplicationEventPublisher publisher) {
		return new ApplicationEventBudgetListener(publisher);
	}

	/**
	 * The Redis ledger, in its own class so an app without the Redis module never loads its types.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ RedisUsageLedger.class, StringRedisTemplate.class })
	@ConditionalOnProperty(prefix = STORE_PREFIX, name = "type", havingValue = "redis")
	static class RedisLedgerConfiguration {

		/** Spend kept in Redis, shared by every instance of the app. */
		@Bean
		@ConditionalOnMissingBean
		UsageLedger redisUsageLedger(StringRedisTemplate redis, TokenAllowanceProperties properties) {
			return new RedisUsageLedger(redis, properties.zone());
		}
	}

	/**
	 * The 429 handler, in its own class so a non-web app never loads its web types.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication
	@ConditionalOnBooleanProperty(prefix = TokenAllowanceProperties.PREFIX + ".web", name = "exception-handler",
			matchIfMissing = true)
	static class WebConfiguration {

		/** Answers a used-up budget with 429 and a ProblemDetail body. */
		@Bean
		@ConditionalOnMissingBean
		BudgetExceededExceptionHandler budgetExceededExceptionHandler() {
			return new BudgetExceededExceptionHandler();
		}
	}

	/**
	 * The meters, in their own class so an app without Micrometer never loads its types.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	static class MetricsConfiguration {

		/** Records decisions and charges as {@code token.allowance.*} meters. */
		@Bean
		@ConditionalOnMissingBean
		MicrometerBudgetListener micrometerBudgetListener(MeterRegistry registry) {
			return new MicrometerBudgetListener(registry);
		}
	}

	/**
	 * The actuator endpoint, in its own class so an app without Actuator never loads its types.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(Endpoint.class)
	@ConditionalOnAvailableEndpoint(TokenAllowanceEndpoint.class)
	static class EndpointConfiguration {

		/** Shows current spend per subject; only reachable once the app exposes it. */
		@Bean
		@ConditionalOnMissingBean
		TokenAllowanceEndpoint tokenAllowanceEndpoint(BudgetService budgetService) {
			return new TokenAllowanceEndpoint(budgetService);
		}
	}
}
