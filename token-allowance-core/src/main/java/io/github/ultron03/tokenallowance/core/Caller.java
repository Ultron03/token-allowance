package io.github.ultron03.tokenallowance.core;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Who a request is for: one id per scope, and the plan the caller is on.
 *
 * @param subjectIds the user, tenant or session ids; {@link Scope#GLOBAL} needs none
 * @param plan the caller's plan, such as {@code pro}, or null to get the limits every caller has
 */
public record Caller(Map<Scope, String> subjectIds, @Nullable String plan) {

	/** Copies {@code subjectIds}; a blank plan counts as no plan. */
	public Caller {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		subjectIds = Map.copyOf(subjectIds);
		plan = (plan == null || plan.isBlank()) ? null : plan.strip();
	}
}
