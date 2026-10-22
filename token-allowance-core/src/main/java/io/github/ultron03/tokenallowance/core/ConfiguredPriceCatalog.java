package io.github.ultron03.tokenallowance.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prices from configuration. Matches model names ignoring case, then without a trailing date
 * such as {@code -2024-08-06} or {@code -20250929}, then applies the unknown-model policy.
 */
public class ConfiguredPriceCatalog implements PriceCatalog {

	private static final Pattern DATE_SUFFIX = Pattern.compile("-(\\d{4}-\\d{2}-\\d{2}|\\d{8})$");

	private static final Logger log = LoggerFactory.getLogger(ConfiguredPriceCatalog.class);

	private final Map<String, ModelPrice> prices;

	private final UnknownModelPolicy unknownModelPolicy;

	private final @Nullable ModelPrice defaultPrice;

	private final Set<String> warnedModels;

	/**
	 * Creates the catalog.
	 * @param defaultPrice the price for unknown models; required with {@link UnknownModelPolicy#DEFAULT_PRICE}
	 * @throws IllegalArgumentException if the default price is missing or two model names differ only by case
	 */
	public ConfiguredPriceCatalog(Map<String, ModelPrice> prices, UnknownModelPolicy unknownModelPolicy,
			@Nullable ModelPrice defaultPrice) {
		Objects.requireNonNull(prices, "prices must not be null");
		Objects.requireNonNull(unknownModelPolicy, "unknownModelPolicy must not be null");
		if (unknownModelPolicy == UnknownModelPolicy.DEFAULT_PRICE && defaultPrice == null) {
			throw new IllegalArgumentException("Unknown-model policy DEFAULT_PRICE needs a default price");
		}

		this.prices = lowercaseKeys(prices);
		this.unknownModelPolicy = unknownModelPolicy;
		this.defaultPrice = defaultPrice;
		this.warnedModels = ConcurrentHashMap.newKeySet();
	}

	@Override
	public Optional<ModelPrice> priceFor(String model) {
		Objects.requireNonNull(model, "model must not be null");
		String name = model.strip().toLowerCase(Locale.ROOT);

		@Nullable ModelPrice exact = this.prices.get(name);
		if (exact != null) {
			return Optional.of(exact);
		}

		@Nullable ModelPrice undated = this.prices.get(stripDateSuffix(name));
		if (undated != null) {
			return Optional.of(undated);
		}

		return handleUnknown(name);
	}

	private static Map<String, ModelPrice> lowercaseKeys(Map<String, ModelPrice> prices) {
		Map<String, ModelPrice> lowercased = new HashMap<>();
		for (Map.Entry<String, ModelPrice> entry : prices.entrySet()) {
			String key = entry.getKey().strip().toLowerCase(Locale.ROOT);
			@Nullable ModelPrice previous = lowercased.put(key, entry.getValue());
			if (previous != null) {
				throw new IllegalArgumentException("Model '" + key + "' has more than one price");
			}
		}
		return Map.copyOf(lowercased);
	}

	private static String stripDateSuffix(String name) {
		return DATE_SUFFIX.matcher(name).replaceFirst("");
	}

	private Optional<ModelPrice> handleUnknown(String name) {
		warnOnce(name);
		return switch (this.unknownModelPolicy) {
			case REJECT -> throw new UnknownModelException(name);
			case ALLOW_UNCOSTED -> Optional.empty();
			case DEFAULT_PRICE -> Optional.of(Objects.requireNonNull(this.defaultPrice));
		};
	}

	private void warnOnce(String name) {
		if (this.warnedModels.add(name)) {
			log.warn("No price for model '{}', applying unknown-model policy {}. "
					+ "Add a price for this model to the pricing configuration.", name, this.unknownModelPolicy);
		}
	}
}
