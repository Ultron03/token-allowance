package io.github.ultron03.tokenallowance.springai.advisor;

/**
 * What to charge for a stream that is cancelled, or fails after its first chunk, before the provider
 * reported usage. The provider has usually started billing by then, but the real count is unknown.
 */
public enum InterruptedStreamPolicy {

	/** Charge the reserved estimate. Safe default: may overcharge, never lets a call go free. */
	CHARGE_ESTIMATE,

	/** Charge nothing. May undercharge, for apps that would rather not bill a user for a dropped stream. */
	RELEASE
}
