package io.github.ultron03.tokenallowance.core;

/** What to do with a call to a model that has no configured price. */
public enum UnknownModelPolicy {

	/** Refuse the call. */
	REJECT,

	/** Allow the call and count its tokens, but charge no cost. */
	ALLOW_UNCOSTED,

	/** Allow the call and charge it at the default price. */
	DEFAULT_PRICE
}
