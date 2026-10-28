package io.github.ultron03.tokenallowance.autoconfigure;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import io.github.ultron03.tokenallowance.core.EnforcementMode;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.UnknownModelPolicy;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.springai.advisor.InterruptedStreamPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Settings under {@code token-allowance.*}. Plain data: rules that span fields (a limit needs a cap,
 * {@code default-price} needs a default price) are checked by the core types they are turned into.
 *
 * @param enabled whether Token Allowance is switched on
 * @param mode whether a used-up budget denies requests or is only logged
 * @param zone the time zone in which daily and monthly windows start
 * @param subject how a request's user is found
 * @param pricing model prices and what to do with unknown models
 * @param limits the budgets, one per scope and window
 * @param perRequest caps on one request, to cut off runaway tool loops
 * @param stream how interrupted streams are charged
 * @param web the HTTP response for a used-up budget
 */
@ConfigurationProperties(TokenAllowanceProperties.PREFIX)
@Validated
public record TokenAllowanceProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("enforce") EnforcementMode mode, @DefaultValue("UTC") ZoneId zone,
		@DefaultValue @Valid Subject subject, @DefaultValue @Valid Pricing pricing,
		@DefaultValue List<@Valid LimitProperties> limits, @DefaultValue @Valid PerRequest perRequest,
		@DefaultValue Streaming stream, @DefaultValue Web web) {

	/** Prefix of all Token Allowance properties. */
	public static final String PREFIX = "token-allowance";

	/** Copies the list, so the bound settings can't change later. */
	public TokenAllowanceProperties {
		limits = List.copyOf(limits);
	}

	/**
	 * How a request's user is found when it has no advisor param and no Spring Security principal.
	 *
	 * @param header the HTTP header holding the user id; only trust it behind a gateway that sets it
	 */
	public record Subject(@DefaultValue("X-User-Id") @NotBlank String header) {
	}

	/**
	 * Model prices. A list, not a map, because dots in map keys (such as {@code gpt-4.1}) would be
	 * read as nested properties.
	 *
	 * @param unknownModel what to do with a model that has no price
	 * @param defaultPrice the price for unknown models; required with {@code default-price}
	 * @param models the price of each model
	 */
	public record Pricing(@DefaultValue("reject") UnknownModelPolicy unknownModel,
			@Valid @Nullable PriceProperties defaultPrice, @DefaultValue List<@Valid ModelProperties> models) {

		/** Copies the list, so the bound settings can't change later. */
		public Pricing {
			models = List.copyOf(models);
		}
	}

	/**
	 * A price in dollars per million tokens, as on provider pricing pages.
	 *
	 * @param inputPerMtok the input price
	 * @param outputPerMtok the output price
	 * @param cachedInputPerMtok the cached-input price, or null to use the input price
	 * @param cacheWritePerMtok the cache-write price, or null to use the input price
	 */
	public record PriceProperties(@NotNull @PositiveOrZero BigDecimal inputPerMtok,
			@NotNull @PositiveOrZero BigDecimal outputPerMtok, @PositiveOrZero @Nullable BigDecimal cachedInputPerMtok,
			@PositiveOrZero @Nullable BigDecimal cacheWritePerMtok) {
	}

	/**
	 * The price of one model, in dollars per million tokens.
	 *
	 * @param name the model name; matched ignoring case and a trailing date suffix
	 * @param inputPerMtok the input price
	 * @param outputPerMtok the output price
	 * @param cachedInputPerMtok the cached-input price, or null to use the input price
	 * @param cacheWritePerMtok the cache-write price, or null to use the input price
	 */
	public record ModelProperties(@NotBlank String name, @NotNull @PositiveOrZero BigDecimal inputPerMtok,
			@NotNull @PositiveOrZero BigDecimal outputPerMtok, @PositiveOrZero @Nullable BigDecimal cachedInputPerMtok,
			@PositiveOrZero @Nullable BigDecimal cacheWritePerMtok) {
	}

	/**
	 * One budget. At least one cap must be set.
	 *
	 * @param scope who shares the budget
	 * @param window how long the budget lasts before it resets
	 * @param maxTokens the most tokens in one window, or null for no token cap
	 * @param maxCost the most dollars in one window, or null for no cost cap
	 */
	public record LimitProperties(@NotNull Scope scope, @NotNull Window window, @Positive @Nullable Long maxTokens,
			@Positive @Nullable BigDecimal maxCost) {
	}

	/**
	 * Caps on one request. Unset caps don't apply.
	 *
	 * @param maxModelCalls the most model calls one request may make, or null for no cap
	 * @param maxCost the most dollars one request may cost, or null for no cap
	 */
	public record PerRequest(@Positive @Nullable Integer maxModelCalls, @Positive @Nullable BigDecimal maxCost) {
	}

	/**
	 * Streaming settings.
	 *
	 * @param interrupted what to charge for a stream that ends before the provider reported usage
	 */
	public record Streaming(@DefaultValue("charge-estimate") InterruptedStreamPolicy interrupted) {
	}

	/**
	 * HTTP settings.
	 *
	 * @param exceptionHandler whether a used-up budget is answered with 429 and a ProblemDetail body
	 */
	public record Web(@DefaultValue("true") boolean exceptionHandler) {
	}
}
