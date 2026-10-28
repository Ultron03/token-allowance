package io.github.ultron03.tokenallowance.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.LimitProperties;
import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.ModelProperties;
import io.github.ultron03.tokenallowance.core.EnforcementMode;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.UnknownModelPolicy;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.springai.advisor.InterruptedStreamPolicy;

class TokenAllowancePropertiesTest {

	private static final LocalValidatorFactoryBean validator = createValidator();

	@AfterAll
	static void closeValidator() {
		validator.close();
	}

	@Test
	void bindsDefaultsWhenNothingIsConfigured() {
		TokenAllowanceProperties properties = bind(Map.of());

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.mode()).isEqualTo(EnforcementMode.ENFORCE);
		assertThat(properties.zone()).isEqualTo(ZoneId.of("UTC"));
		assertThat(properties.subject().header()).isEqualTo("X-User-Id");
		assertThat(properties.pricing().unknownModel()).isEqualTo(UnknownModelPolicy.REJECT);
		assertThat(properties.pricing().defaultPrice()).isNull();
		assertThat(properties.pricing().models()).isEmpty();
		assertThat(properties.limits()).isEmpty();
		assertThat(properties.perRequest().maxModelCalls()).isNull();
		assertThat(properties.perRequest().maxCost()).isNull();
		assertThat(properties.stream().interrupted()).isEqualTo(InterruptedStreamPolicy.CHARGE_ESTIMATE);
		assertThat(properties.web().exceptionHandler()).isTrue();
	}

	@Test
	void bindsModelNameContainingDots() {
		TokenAllowanceProperties properties = bind(Map.of(
				"token-allowance.pricing.models[0].name", "gpt-4.1",
				"token-allowance.pricing.models[0].input-per-mtok", "2.00",
				"token-allowance.pricing.models[0].output-per-mtok", "8.00"));

		assertThat(properties.pricing().models()).extracting(ModelProperties::name).containsExactly("gpt-4.1");
	}

	@Test
	void bindsKebabCaseEnumValues() {
		TokenAllowanceProperties properties = bind(Map.of(
				"token-allowance.mode", "observe",
				"token-allowance.pricing.unknown-model", "allow-uncosted",
				"token-allowance.stream.interrupted", "release"));

		assertThat(properties.mode()).isEqualTo(EnforcementMode.OBSERVE);
		assertThat(properties.pricing().unknownModel()).isEqualTo(UnknownModelPolicy.ALLOW_UNCOSTED);
		assertThat(properties.stream().interrupted()).isEqualTo(InterruptedStreamPolicy.RELEASE);
	}

	@Test
	void bindsLimitWithoutTokenCapAsNull() {
		TokenAllowanceProperties properties = bind(Map.of(
				"token-allowance.limits[0].scope", "user",
				"token-allowance.limits[0].window", "daily",
				"token-allowance.limits[0].max-cost", "1.00"));

		LimitProperties limit = properties.limits().get(0);
		assertThat(limit.scope()).isEqualTo(Scope.USER);
		assertThat(limit.window()).isEqualTo(Window.DAILY);
		assertThat(limit.maxTokens()).isNull();
		assertThat(limit.maxCost()).isEqualByComparingTo("1.00");
	}

	@Test
	void keepsMoneyAmountsExact() {
		TokenAllowanceProperties properties = bind(Map.of(
				"token-allowance.pricing.default-price.input-per-mtok", "0.10",
				"token-allowance.pricing.default-price.output-per-mtok", "0.000001"));

		TokenAllowanceProperties.PriceProperties price = properties.pricing().defaultPrice();
		assertThat(price).isNotNull();
		assertThat(price.inputPerMtok()).isEqualTo(new BigDecimal("0.10"));
		assertThat(price.outputPerMtok()).isEqualTo(new BigDecimal("0.000001"));
	}

	@Test
	void rejectsLimitWithoutScope() {
		assertThatThrownBy(() -> bind(Map.of(
				"token-allowance.limits[0].window", "daily",
				"token-allowance.limits[0].max-cost", "1.00")))
			.isInstanceOf(BindException.class)
			.rootCause()
			.isInstanceOf(BindValidationException.class)
			.hasMessageContaining("limits[0].scope");
	}

	@Test
	void rejectsPerRequestCapOfZero() {
		assertThatThrownBy(() -> bind(Map.of("token-allowance.per-request.max-model-calls", "0")))
			.isInstanceOf(BindException.class)
			.rootCause()
			.isInstanceOf(BindValidationException.class)
			.hasMessageContaining("per-request.max-model-calls");
	}

	private static TokenAllowanceProperties bind(Map<String, String> properties) {
		Binder binder = new Binder(new MapConfigurationPropertySource(properties));
		return binder.bindOrCreate(TokenAllowanceProperties.PREFIX, Bindable.of(TokenAllowanceProperties.class),
				new ValidationBindHandler(validator));
	}

	private static LocalValidatorFactoryBean createValidator() {
		LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
		factory.afterPropertiesSet();
		return factory;
	}
}
