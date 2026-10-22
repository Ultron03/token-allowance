package io.github.ultron03.tokenallowance.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Prices of one model, per million tokens, as shown on provider pricing pages.
 *
 * @param inputPerMTok price of uncached input tokens
 * @param outputPerMTok price of output tokens
 * @param cachedInputPerMTok price of cache reads; the input price if {@code null}
 * @param cacheWritePerMTok price of cache writes; the input price if {@code null}
 */
public record ModelPrice(Money inputPerMTok, Money outputPerMTok, @Nullable Money cachedInputPerMTok,
		@Nullable Money cacheWritePerMTok) {

	private static final BigDecimal TOKENS_PER_MTOK = BigDecimal.valueOf(1_000_000);

	/**
	 * Validates the required prices.
	 * @throws NullPointerException if the input or output price is {@code null}
	 */
	public ModelPrice {
		Objects.requireNonNull(inputPerMTok, "inputPerMTok must not be null");
		Objects.requireNonNull(outputPerMTok, "outputPerMTok must not be null");
	}

	/**
	 * {@return the cost of {@code usage}, rounded up once to a whole micro-dollar}
	 * @throws ArithmeticException on overflow
	 */
	public Money costOf(TokenUsage usage) {
		long uncachedInput = usage.input() - usage.cachedInput() - usage.cacheWriteInput();

		long uncachedInputCost = Math.multiplyExact(uncachedInput, inputPerMTok.micros());
		long cachedInputCost = Math.multiplyExact(usage.cachedInput(), orInputPrice(cachedInputPerMTok).micros());
		long cacheWriteCost = Math.multiplyExact(usage.cacheWriteInput(), orInputPrice(cacheWritePerMTok).micros());
		long outputCost = Math.multiplyExact(usage.output(), outputPerMTok.micros());

		long totalMicrosPerMTok = Math.addExact(Math.addExact(uncachedInputCost, cachedInputCost),
				Math.addExact(cacheWriteCost, outputCost));

		long micros = BigDecimal.valueOf(totalMicrosPerMTok)
			.divide(TOKENS_PER_MTOK, 0, RoundingMode.CEILING)
			.longValueExact();
		return new Money(micros);
	}

	private Money orInputPrice(@Nullable Money price) {
		return (price != null) ? price : inputPerMTok;
	}
}
