package io.github.ultron03.tokenallowance.core;

/** Whether budgets block calls or are only measured. */
public enum EnforcementMode {

	/** Record spend and log when a budget is exceeded, but never block; safe to adopt first. */
	OBSERVE,

	/** Block calls when a budget is exceeded. */
	ENFORCE
}
