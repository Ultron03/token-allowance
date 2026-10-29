package io.github.ultron03.tokenallowance.autoconfigure;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.LimitProperties;
import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.ModelProperties;
import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.PerRequest;
import io.github.ultron03.tokenallowance.autoconfigure.TokenAllowanceProperties.Pricing;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.ModelPrice;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.RequestLimit;

/**
 * Turns the bound properties into core types. Dollar amounts become {@link Money}; a price that
 * looks like a per-token price fails (more than six decimals) or is logged (very small), since
 * prices are per million tokens.
 */
final class PropertyMapping {

	/** Real per-million prices are far above this; per-token prices are far below. */
	private static final BigDecimal PER_TOKEN_SUSPECT_BELOW = new BigDecimal("0.001");

	private static final Logger log = LoggerFactory.getLogger(PropertyMapping.class);

	private PropertyMapping() {
	}

	/**
	 * Returns the dollar amount as {@link Money}.
	 * @throws NullPointerException if the amount is missing (possible when no validator is present)
	 * @throws IllegalArgumentException if it has more than six decimal places or is too large
	 */
	static Money money(BigDecimal dollars) {
		Objects.requireNonNull(dollars, "a required price or cost is missing");
		try {
			return Money.ofDollars(dollars.toPlainString());
		}
		catch (ArithmeticException ex) {
			throw new IllegalArgumentException("Amount " + dollars.toPlainString()
					+ " has more than 6 decimal places or is too large. Prices are per million tokens,"
					+ " as on provider pricing pages; a value this precise is usually a per-token price.", ex);
		}
	}

	/** Returns the dollar amount as {@link Money}, or null if it is not set. */
	static @Nullable Money moneyOrNull(@Nullable BigDecimal dollars) {
		return (dollars != null) ? money(dollars) : null;
	}

	/** Returns the price of each configured model, by name. */
	static Map<String, ModelPrice> prices(List<ModelProperties> models) {
		return models.stream()
			.collect(Collectors.toMap(ModelProperties::name, model -> modelPrice(model.name(), model.inputPerMtok(),
					model.outputPerMtok(), model.cachedInputPerMtok(), model.cacheWritePerMtok())));
	}

	/** Returns the price for unknown models, or null if none is set. */
	static @Nullable ModelPrice defaultPrice(Pricing pricing) {
		TokenAllowanceProperties.PriceProperties price = pricing.defaultPrice();
		if (price == null) {
			return null;
		}
		return modelPrice("default-price", price.inputPerMtok(), price.outputPerMtok(), price.cachedInputPerMtok(),
				price.cacheWritePerMtok());
	}

	/** Returns the configured limits; each must set at least one cap. */
	static List<Limit> limits(List<LimitProperties> limits) {
		return limits.stream()
			.map(limit -> new Limit(limit.scope(), limit.window(), limit.maxTokens(), moneyOrNull(limit.maxCost())))
			.toList();
	}

	/** Returns the caps on one request; unset caps don't apply. */
	static RequestLimit requestLimit(PerRequest perRequest) {
		return new RequestLimit(perRequest.maxModelCalls(), moneyOrNull(perRequest.maxCost()));
	}

	private static ModelPrice modelPrice(String model, BigDecimal input, BigDecimal output,
			@Nullable BigDecimal cachedInput, @Nullable BigDecimal cacheWrite) {
		warnIfPerTokenPrice(model + " input-per-mtok", input);
		warnIfPerTokenPrice(model + " output-per-mtok", output);
		warnIfPerTokenPrice(model + " cached-input-per-mtok", cachedInput);
		warnIfPerTokenPrice(model + " cache-write-per-mtok", cacheWrite);
		return new ModelPrice(money(input), money(output), moneyOrNull(cachedInput), moneyOrNull(cacheWrite));
	}

	private static void warnIfPerTokenPrice(String what, @Nullable BigDecimal price) {
		if (price == null || price.signum() == 0 || price.compareTo(PER_TOKEN_SUSPECT_BELOW) >= 0) {
			return;
		}
		log.warn("Token Allowance price {} is {} per million tokens, which looks like a per-token price."
				+ " Prices are per million tokens, as on provider pricing pages.", what, price.toPlainString());
	}
}
