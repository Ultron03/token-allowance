package io.github.ultron03.tokenallowance.core;

/** Thrown when a model has no price and the unknown-model policy rejects the call. */
public class UnknownModelException extends RuntimeException {

	private final String model;

	/** Creates the exception for {@code model}. */
	public UnknownModelException(String model) {
		super("No price configured for model '" + model + "'");
		this.model = model;
	}

	/** {@return the model that has no price} */
	public String model() {
		return this.model;
	}
}
