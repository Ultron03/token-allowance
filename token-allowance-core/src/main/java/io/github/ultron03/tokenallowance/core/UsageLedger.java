package io.github.ultron03.tokenallowance.core;

import java.util.Map;
import java.util.Set;

/**
 * Stores spend per budget counter. Replace this bean to keep spend elsewhere, for example in Redis.
 *
 * <p>Implementations must check and reserve all keys in one atomic step, so two parallel calls
 * can't both pass the check and overspend together.
 */
public interface UsageLedger {

	/**
	 * Reserves {@code estimate} on every key, or on none if any limit would be exceeded.
	 * Reaching a limit exactly is allowed.
	 * @param limits the limit to check for each counter; every key in the map is reserved on
	 * @throws IllegalArgumentException if a limit's scope or window doesn't match its key
	 */
	ReservationResult reserve(Map<BudgetKey, Limit> limits, Amount estimate);

	/** Reserves {@code estimate} on every key without checking limits, so observe mode still records spend. */
	Reservation reserveUnchecked(Set<BudgetKey> keys, Amount estimate);

	/**
	 * Replaces the reservation with the actual amount. Never denied, because the call already
	 * happened; does nothing if the reservation is already settled.
	 */
	void commit(Reservation reservation, Amount actual);

	/** Drops the reservation without charging; does nothing if it is already settled. */
	void release(Reservation reservation);

	/** {@return the committed and reserved spend of {@code key}, or zero if nothing is recorded} */
	Spend current(BudgetKey key);
}
