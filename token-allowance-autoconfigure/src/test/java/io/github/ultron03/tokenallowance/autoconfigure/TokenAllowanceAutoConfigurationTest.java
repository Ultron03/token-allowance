package io.github.ultron03.tokenallowance.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.PriceCatalog;
import io.github.ultron03.tokenallowance.core.TokenEstimator;
import io.github.ultron03.tokenallowance.core.UsageLedger;
import io.github.ultron03.tokenallowance.springai.advisor.AdmissionAdvisor;
import io.github.ultron03.tokenallowance.springai.advisor.MeteringAdvisor;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;
import io.github.ultron03.tokenallowance.springai.web.BudgetExceededExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;

class TokenAllowanceAutoConfigurationTest {

	private static final String CUSTOMIZER = TokenAllowanceAutoConfiguration.CUSTOMIZER_BEAN_NAME;

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
	void keepsUserLedger() {
		UsageLedger ledger = new InMemoryUsageLedger(Clock.systemUTC(), ZoneOffset.UTC);

		runner.withBean(UsageLedger.class, () -> ledger)
			.run(context -> assertThat(context.getBean(UsageLedger.class)).isSameAs(ledger));
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
}
