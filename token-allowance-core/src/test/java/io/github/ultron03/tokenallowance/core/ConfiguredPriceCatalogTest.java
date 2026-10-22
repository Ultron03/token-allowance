package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ConfiguredPriceCatalogTest {

	private static final ModelPrice GPT_4O = new ModelPrice(Money.ofDollars("2.50"), Money.ofDollars("10.00"),
			null, null);

	private static final ModelPrice FALLBACK = new ModelPrice(Money.ofDollars("5.00"), Money.ofDollars("20.00"),
			null, null);

	private static final Map<String, ModelPrice> PRICES = Map.of("gpt-4o", GPT_4O);

	@Test
	void findsPriceForExactModelName() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("gpt-4o");

		assertThat(result).contains(GPT_4O);
	}

	@Test
	void matchesModelNameIgnoringCase() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("GPT-4o");

		assertThat(result).contains(GPT_4O);
	}

	@Test
	void matchesConfiguredKeyIgnoringCase() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(Map.of("GPT-4o", GPT_4O),
				UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("gpt-4o");

		assertThat(result).contains(GPT_4O);
	}

	@Test
	void stripsDashedDateSuffix() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("gpt-4o-2024-08-06");

		assertThat(result).contains(GPT_4O);
	}

	@Test
	void stripsCompactDateSuffix() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(Map.of("claude-sonnet-4-5", GPT_4O),
				UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("claude-sonnet-4-5-20250929");

		assertThat(result).contains(GPT_4O);
	}

	@Test
	void prefersExactDatedPriceOverUndatedPrice() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(
				Map.of("gpt-4o", GPT_4O, "gpt-4o-2024-08-06", FALLBACK), UnknownModelPolicy.REJECT, null);

		Optional<ModelPrice> result = catalog.priceFor("gpt-4o-2024-08-06");

		assertThat(result).contains(FALLBACK);
	}

	@Test
	void doesNotMatchByPrefix() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.ALLOW_UNCOSTED, null);

		Optional<ModelPrice> result = catalog.priceFor("gpt-4o-mini");

		assertThat(result).isEmpty();
	}

	@Test
	void rejectsUnknownModelWhenPolicyIsReject() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.REJECT, null);

		assertThatThrownBy(() -> catalog.priceFor("no-such-model"))
			.isInstanceOf(UnknownModelException.class)
			.hasMessageContaining("no-such-model");
	}

	@Test
	void returnsEmptyForUnknownModelWhenUncostedAllowed() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.ALLOW_UNCOSTED, null);

		Optional<ModelPrice> result = catalog.priceFor("no-such-model");

		assertThat(result).isEmpty();
	}

	@Test
	void returnsDefaultPriceForUnknownModel() {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.DEFAULT_PRICE,
				FALLBACK);

		Optional<ModelPrice> result = catalog.priceFor("no-such-model");

		assertThat(result).contains(FALLBACK);
	}

	@Test
	void rejectsDefaultPricePolicyWithoutDefaultPrice() {
		assertThatThrownBy(() -> new ConfiguredPriceCatalog(PRICES, UnknownModelPolicy.DEFAULT_PRICE, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsKeysThatDifferOnlyByCase() {
		Map<String, ModelPrice> duplicates = Map.of("GPT-4o", GPT_4O, "gpt-4o", GPT_4O);

		assertThatThrownBy(() -> new ConfiguredPriceCatalog(duplicates, UnknownModelPolicy.REJECT, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("gpt-4o");
	}
}
