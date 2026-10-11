package io.github.ultron03.tokenallowance.core;

/** Who a budget belongs to. */
public enum Scope {

	/** One end user. */
	USER,

	/** An organisation or customer account, shared by its users. */
	TENANT,

	/** One conversation. */
	SESSION,

	/** The whole application. */
	GLOBAL
}
