package io.github.ultron03.tokenallowance.core;

import java.util.List;

/**
 * Decides which limits apply to a caller. Replace this bean to read limits from your own store,
 * for example a customer's contracted cap.
 */
@FunctionalInterface
public interface LimitResolver {

	/**
	 * Called before every model call and when a call is settled, so cache slow lookups.
	 * @return the caller's limits, at most one per scope and window
	 */
	List<Limit> limitsFor(Caller caller);
}
