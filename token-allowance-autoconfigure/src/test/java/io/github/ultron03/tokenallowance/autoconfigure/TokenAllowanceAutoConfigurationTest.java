package io.github.ultron03.tokenallowance.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.BudgetListener;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.BudgetThresholdReached;
import io.github.ultron03.tokenallowance.core.Caller;
import io.github.ultron03.tokenallowance.core.CallSettled;
import io.github.ultron03.tokenallowance.core.Decision;
import io.github.ultron03.tokenallowance.core.DecisionMade;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.LedgerUnavailableException;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.LimitResolver;
import io.github.ultron03.tokenallowance.core.PriceCatalog;
import io.github.ultron03.tokenallowance.core.RequestLimit;
import io.github.ultron03.tokenallowance.core.Reservation;
import io.github.ultron03.tokenallowance.core.ReservationResult;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Spend;
import io.github.ultron03.tokenallowance.core.TokenEstimator;
import io.github.ultron03.tokenallowance.core.TokenUsage;
import io.github.ultron03.tokenallowance.core.UsageLedger;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.redis.RedisUsageLedger;
import io.github.ultron03.tokenallowance.springai.actuator.TokenAllowanceEndpoint;
import io.github.ultron03.tokenallowance.springai.advisor.AdmissionAdvisor;
import io.github.ultron03.tokenallowance.springai.advisor.MeteringAdvisor;
import io.github.ultron03.tokenallowance.springai.metrics.MicrometerBudgetListener;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;
import io.github.ultron03.tokenallowance.springai.web.BudgetExceededExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;

class TokenAllowanceAutoConfigurationTest {

	private static final String CUSTOMIZER = TokenAllowanceAutoConfiguration.CUSTOMIZER_BEAN_NAME;

	private static final String[] USER_DAILY_LIMIT = { "token-allowance.limits[0].scope=user",
			"token-allowance.limits[0].window=daily", "token-allowance.limits[0].max-tokens=1000" };

	private static final Caller ALICE = new Caller(Map.of(Scope.USER, "alice"), null);

	private static final Caller ALICE_ON_PRO = new Caller(Map.of(Scope.USER, "alice"), "pro");

	private static final String ALLOW_UNCOSTED = "token-allowance.pricing.unknown-model=allow-uncosted";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TokenAllowanceAutoConfiguration.class));

	private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TokenAllowanceAutoConfiguration.class));

	@Test
	void isListedInAutoConfigurationImports() {
		ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());

		assertThat(candidates.getCandidates()).contains(TokenAllowanceAutoConfiguration.class.getName());
	}

	@Test
	void registersAllBeansByDefault() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(TokenAllowanceProperties.class);
			assertThat(context).hasSingleBean(Clock.class);
			assertThat(context).hasSingleBean(TokenEstimator.class);
			assertThat(context).hasSingleBean(PriceCatalog.class);
			assertThat(context).hasSingleBean(UsageLedger.class);
			assertThat(context).hasSingleBean(BudgetService.class);
			assertThat(context).hasSingleBean(SubjectResolver.class);
			assertThat(context).hasSingleBean(AdmissionAdvisor.class);
			assertThat(context).hasSingleBean(MeteringAdvisor.class);
			assertThat(context).hasBean(CUSTOMIZER);
		});
	}

	@Test
	void registersNothingWhenDisabled() {
		runner.withPropertyValues("token-allowance.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(TokenAllowanceProperties.class);
			assertThat(context).doesNotHaveBean(BudgetService.class);
			assertThat(context).doesNotHaveBean(CUSTOMIZER);
		});
	}

	@Test
	void registersNothingWithoutSpringAiChatClient() {
		runner.withClassLoader(new FilteredClassLoader(ChatClient.class))
			.run(context -> assertThat(context).doesNotHaveBean(BudgetService.class));
	}

	@Test
	void keepsSpendInMemoryByDefault() {
		runner.run(context -> assertThat(context.getBean(UsageLedger.class)).isInstanceOf(InMemoryUsageLedger.class));
	}

	@Test
	void keepsSpendInRedisWhenStoreTypeIsRedis() {
		runner.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
			.withPropertyValues("token-allowance.store.type=redis")
			.run(context -> assertThat(context.getBean(UsageLedger.class)).isInstanceOf(RedisUsageLedger.class));
	}

	@Test
	void failsStartupWhenRedisStoreIsChosenWithoutRedisModule() {
		runner.withClassLoader(new FilteredClassLoader(RedisUsageLedger.class))
			.withPropertyValues("token-allowance.store.type=redis")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasStackTraceContaining(UsageLedger.class.getName());
			});
	}

	@Test
	void failsClosedWhenStoreIsDownInEnforceModeByDefault() {
		runner.withBean(UsageLedger.class, UnavailableLedger::new)
			.withPropertyValues(USER_DAILY_LIMIT)
			.run(context -> assertThatThrownBy(() -> context.getBean(BudgetService.class).admit(ALICE))
				.isInstanceOf(LedgerUnavailableException.class));
	}

	@Test
	void failsOpenWhenStoreIsDownAndFailOpenIsConfigured() {
		runner.withBean(UsageLedger.class, UnavailableLedger::new)
			.withPropertyValues(USER_DAILY_LIMIT)
			.withPropertyValues("token-allowance.store.failure-mode=fail-open")
			.run(context -> assertThat(context.getBean(BudgetService.class).admit(ALICE))
				.isEqualTo(new Decision.Allowed()));
	}

	@Test
	void keepsUserLedger() {
		UsageLedger ledger = new InMemoryUsageLedger(Clock.systemUTC(), ZoneOffset.UTC);

		runner.withBean(UsageLedger.class, () -> ledger)
			.run(context -> assertThat(context.getBean(UsageLedger.class)).isSameAs(ledger));
	}

	@Test
	void reservesConfiguredOutputEstimate() {
		runner.withPropertyValues("token-allowance.estimate.output-tokens=50")
			.run(context -> assertThat(context.getBean(TokenEstimator.class).estimateOutputTokens(5000)).isEqualTo(50));
	}

	@Test
	void reservesMaxTokensForOutputByDefault() {
		runner.run(context -> assertThat(context.getBean(TokenEstimator.class).estimateOutputTokens(5000))
			.isEqualTo(5000));
	}

	@Test
	void failsStartupForOutputEstimateOfZero() {
		runner.withPropertyValues("token-allowance.estimate.output-tokens=0")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void tokenOnlyLimitsWorkWithoutPrices() {
		runner.withPropertyValues(USER_DAILY_LIMIT).run(context -> {
			BudgetService service = context.getBean(BudgetService.class);
			chargeAlice(service, 1000);

			assertThat(service.admit(ALICE)).isInstanceOf(Decision.Denied.class);
		});
	}

	@Test
	void appliesPlanLimitsFromProperties() {
		runner.withPropertyValues(USER_DAILY_LIMIT)
			.withPropertyValues(ALLOW_UNCOSTED, "token-allowance.plans.pro[0].scope=user",
					"token-allowance.plans.pro[0].window=daily", "token-allowance.plans.pro[0].max-tokens=5000")
			.run(context -> {
				BudgetService service = context.getBean(BudgetService.class);
				chargeAlice(service, 1000);

				assertThat(service.admit(ALICE)).isInstanceOf(Decision.Denied.class);
				assertThat(service.admit(ALICE_ON_PRO)).isEqualTo(new Decision.Allowed());
			});
	}

	@Test
	void usesUserLimitResolver() {
		LimitResolver contractCaps = caller -> List.of(new Limit(Scope.USER, Window.DAILY, 10L, null));

		runner.withPropertyValues(ALLOW_UNCOSTED)
			.withBean(LimitResolver.class, () -> contractCaps)
			.run(context -> {
				BudgetService service = context.getBean(BudgetService.class);
				chargeAlice(service, 10);

				assertThat(service.admit(ALICE)).isInstanceOf(Decision.Denied.class);
			});
	}

	@Test
	void failsStartupForGlobalLimitInPlan() {
		runner.withPropertyValues("token-allowance.plans.pro[0].scope=global",
				"token-allowance.plans.pro[0].window=daily", "token-allowance.plans.pro[0].max-tokens=5000")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasStackTraceContaining("GLOBAL limit");
			});
	}

	@Test
	void keepsUserClock() {
		Clock clock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);

		runner.withBean(Clock.class, () -> clock).run(context -> assertThat(context.getBean(Clock.class)).isSameAs(clock));
	}

	@Test
	void keepsBuiltInCustomizerNextToUserCustomizer() {
		runner.withBean("userCustomizer", ChatClientBuilderCustomizer.class, () -> builder -> {
		}).run(context -> {
			assertThat(context.getBeansOfType(ChatClientBuilderCustomizer.class)).containsOnlyKeys("userCustomizer",
					CUSTOMIZER);
		});
	}

	@Test
	void replacesCustomizerWithSameName() {
		ChatClientBuilderCustomizer userCustomizer = builder -> {
		};

		runner.withBean(CUSTOMIZER, ChatClientBuilderCustomizer.class, () -> userCustomizer)
			.run(context -> assertThat(context.getBean(CUSTOMIZER)).isSameAs(userCustomizer));
	}

	@Test
	void customizerAddsAdmissionAndMeteringAdvisors() {
		runner.run(context -> {
			ChatClient.Builder builder = ChatClient.builder(prompt -> {
				throw new UnsupportedOperationException("no model call expected");
			});

			context.getBean(CUSTOMIZER, ChatClientBuilderCustomizer.class).customize(builder);

			DefaultChatClientRequestSpec request = (DefaultChatClientRequestSpec) builder.build().prompt();
			assertThat(request.getAdvisors()).contains(context.getBean(AdmissionAdvisor.class),
					context.getBean(MeteringAdvisor.class));
		});
	}

	@Test
	void startsWithoutServletApi() {
		runner.withClassLoader(new FilteredClassLoader(HttpServletRequest.class)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SubjectResolver.class);
		});
	}

	@Test
	void registersExceptionHandlerInWebApp() {
		webRunner.run(context -> assertThat(context).hasSingleBean(BudgetExceededExceptionHandler.class));
	}

	@Test
	void registersNoExceptionHandlerOutsideWebApp() {
		runner.run(context -> assertThat(context).doesNotHaveBean(BudgetExceededExceptionHandler.class));
	}

	@Test
	void registersNoExceptionHandlerWhenTurnedOff() {
		webRunner.withPropertyValues("token-allowance.web.exception-handler=false")
			.run(context -> assertThat(context).doesNotHaveBean(BudgetExceededExceptionHandler.class));
	}

	@Test
	void recordsDecisionsInMeterRegistry() {
		runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
			assertThat(context).hasSingleBean(MicrometerBudgetListener.class);

			context.getBean(BudgetService.class).admit(ALICE);

			assertThat(context.getBean(MeterRegistry.class).find(MicrometerBudgetListener.DECISIONS).counter())
				.isNotNull();
		});
	}

	@Test
	void publishesReachedThresholdToEventListeners() {
		runner.withUserConfiguration(EventRecorder.class)
			.withPropertyValues(USER_DAILY_LIMIT)
			.withPropertyValues(ALLOW_UNCOSTED)
			.run(context -> {
				chargeAlice(context.getBean(BudgetService.class), 800);

				assertThat(context.getBean(EventRecorder.class).reached)
					.extracting(BudgetThresholdReached::percent)
					.containsExactly(80);
			});
	}

	@Test
	void publishesSettledCallToEventListeners() {
		runner.withUserConfiguration(EventRecorder.class)
			.withPropertyValues(USER_DAILY_LIMIT)
			.withPropertyValues(ALLOW_UNCOSTED)
			.run(context -> {
				chargeAlice(context.getBean(BudgetService.class), 100);

				assertThat(context.getBean(EventRecorder.class).settled).singleElement()
					.extracting(CallSettled::outcome)
					.isEqualTo(CallSettled.Outcome.CHARGED);
			});
	}

	@Test
	void failsStartupForThresholdAbove100() {
		runner.withPropertyValues("token-allowance.thresholds=80,101").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
		});
	}

	@Test
	void registersNoMeterListenerWithoutMeterRegistry() {
		runner.run(context -> assertThat(context).doesNotHaveBean(MicrometerBudgetListener.class));
	}

	@Test
	void startsWithoutMicrometer() {
		runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(MicrometerBudgetListener.class);
		});
	}

	@Test
	void keepsUserMeterListener() {
		MicrometerBudgetListener listener = new MicrometerBudgetListener(new SimpleMeterRegistry());

		runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.withBean(MicrometerBudgetListener.class, () -> listener)
			.run(context -> assertThat(context.getBean(MicrometerBudgetListener.class)).isSameAs(listener));
	}

	@Test
	void passesUserListenerToBudgetService() {
		List<DecisionMade> decisions = new ArrayList<>();
		BudgetListener listener = new BudgetListener() {

			@Override
			public void onDecision(DecisionMade event) {
				decisions.add(event);
			}
		};

		runner.withBean(BudgetListener.class, () -> listener).run(context -> {
			context.getBean(BudgetService.class).admit(ALICE);

			assertThat(decisions).hasSize(1);
		});
	}

	@Test
	void registersEndpointWhenExposedOverWeb() {
		runner.withPropertyValues("management.endpoints.web.exposure.include=" + TokenAllowanceEndpoint.ID)
			.run(context -> assertThat(context).hasSingleBean(TokenAllowanceEndpoint.class));
	}

	@Test
	void mapsEndpointSelectorsToWebPath() {
		webRunner
			.withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class))
			.withPropertyValues("management.endpoints.web.exposure.include=" + TokenAllowanceEndpoint.ID)
			.run(context -> {
				assertThat(context).hasNotFailed();
				ExposableWebEndpoint endpoint = context.getBean(WebEndpointsSupplier.class)
					.getEndpoints()
					.stream()
					.filter(candidate -> candidate.getEndpointId().equals(EndpointId.of(TokenAllowanceEndpoint.ID)))
					.findFirst()
					.orElseThrow();
				assertThat(endpoint.getOperations()).extracting(operation -> operation.getRequestPredicate().getPath())
					.containsExactly(TokenAllowanceEndpoint.ID + "/{scope}/{subjectId}");
			});
	}

	@Test
	void registersNoEndpointUnlessExposed() {
		runner.run(context -> assertThat(context).doesNotHaveBean(TokenAllowanceEndpoint.class));
	}

	@Test
	void startsWithoutActuator() {
		runner.withClassLoader(new FilteredClassLoader(Endpoint.class))
			.withPropertyValues("management.endpoints.web.exposure.include=" + TokenAllowanceEndpoint.ID)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(TokenAllowanceEndpoint.class);
			});
	}

	@Test
	void failsStartupForPerTokenPrice() {
		runner.withPropertyValues("token-allowance.pricing.models[0].name=gpt-4o",
				"token-allowance.pricing.models[0].input-per-mtok=0.0000025",
				"token-allowance.pricing.models[0].output-per-mtok=10.00")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasStackTraceContaining("has more than 6 decimal places");
			});
	}

	@Test
	void failsStartupForInvalidProperty() {
		runner.withPropertyValues("token-allowance.subject.header=")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
			});
	}

	private static void chargeAlice(BudgetService service, long inputTokens) {
		TokenUsage usage = new TokenUsage(inputTokens, 0, 0, 0);
		Decision decision = service.reserve(ALICE, RequestLimit.NONE, "any-model", usage);
		service.settle(ALICE, ((Decision.Reserved) decision).reservation(), "any-model", usage);
	}

	static class EventRecorder {

		private final List<BudgetThresholdReached> reached = new ArrayList<>();

		private final List<CallSettled> settled = new ArrayList<>();

		@EventListener
		void on(BudgetThresholdReached event) {
			this.reached.add(event);
		}

		@EventListener
		void on(CallSettled event) {
			this.settled.add(event);
		}
	}

	private static final class UnavailableLedger implements UsageLedger {

		@Override
		public ReservationResult reserve(Map<BudgetKey, Limit> limits, Amount estimate) {
			throw down();
		}

		@Override
		public Reservation reserveUnchecked(Set<BudgetKey> keys, Amount estimate) {
			throw down();
		}

		@Override
		public Map<BudgetKey, Amount> commit(Reservation reservation, Amount actual) {
			throw down();
		}

		@Override
		public void release(Reservation reservation) {
			throw down();
		}

		@Override
		public Spend current(BudgetKey key) {
			throw down();
		}

		@Override
		public void refund(BudgetKey key, Amount amount) {
			throw down();
		}

		private static LedgerUnavailableException down() {
			return new LedgerUnavailableException("store down", new IllegalStateException("connection refused"));
		}
	}
}
