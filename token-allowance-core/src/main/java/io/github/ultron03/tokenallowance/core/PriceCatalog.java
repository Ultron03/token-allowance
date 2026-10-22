package io.github.ultron03.tokenallowance.core;

import java.util.Optional;

/** Looks up the price of a model. Replace this bean to load prices from elsewhere. */
public interface PriceCatalog {

	/**
	 * {@return the price of {@code model}, or empty if the call is allowed without a cost}
	 * @throws UnknownModelException if the model has no price and the call must be rejected
	 */
	Optional<ModelPrice> priceFor(String model);
}
