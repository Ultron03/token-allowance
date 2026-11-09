package io.github.ultron03.tokenallowance.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limits from configuration: one list every caller gets, and per plan the limits that differ. A
 * plan's limit replaces the common limit of the same scope and window and adds the others.
 *
 * <p>Plans can't change {@link Scope#GLOBAL} limits, since one shared budget can't have a cap per
 * caller. Plan names match ignoring case; an unknown plan gets the common limits and is logged once.
 */
public class PlanLimits implements LimitResolver {

	private static final Logger log = LoggerFactory.getLogger(PlanLimits.class);

	private final List<Limit> limits;

	private final Map<String, List<Limit>> limitsByPlan;

	private final Set<String> unknownPlans = ConcurrentHashMap.newKeySet();

	/**
	 * Creates the resolver.
	 * @param limits the limits of every caller
	 * @param plans per plan name, the limits that differ from {@code limits}
	 * @throws IllegalArgumentException if a list has two limits of one scope and window, a plan has a
	 *         global limit, or two plan names differ only in case
	 */
	public PlanLimits(List<Limit> limits, Map<String, List<Limit>> plans) {
		Objects.requireNonNull(limits, "limits can't be null");
		Objects.requireNonNull(plans, "plans can't be null");
		requireOnePerScopeAndWindow(limits);
		this.limits = List.copyOf(limits);

		Map<String, List<Limit>> byPlan = new HashMap<>();
		plans.forEach((plan, planLimits) -> {
			requireOnePerScopeAndWindow(planLimits);
			requireNoGlobalLimit(plan, planLimits);
			if (byPlan.put(normalise(plan), merge(this.limits, planLimits)) != null) {
				throw new IllegalArgumentException("Plan '" + plan + "' is configured twice with different case");
			}
		});
		this.limitsByPlan = Map.copyOf(byPlan);
	}

	@Override
	public List<Limit> limitsFor(Caller caller) {
		@Nullable String plan = caller.plan();
		if (plan == null) {
			return this.limits;
		}
		@Nullable List<Limit> planLimits = this.limitsByPlan.get(normalise(plan));
		if (planLimits == null) {
			warnUnknownPlanOnce(plan);
			return this.limits;
		}
		return planLimits;
	}

	/**
	 * Rejects a list with two limits of the same scope and window, which would be ambiguous.
	 * @throws IllegalArgumentException naming the duplicated scope and window
	 */
	static void requireOnePerScopeAndWindow(List<Limit> limits) {
		Set<String> seen = new HashSet<>();
		for (Limit limit : limits) {
			if (!seen.add(limit.scope() + "/" + limit.window())) {
				throw new IllegalArgumentException("More than one " + limit.scope() + " " + limit.window()
						+ " limit; put both caps in one limit");
			}
		}
	}

	private static void requireNoGlobalLimit(String plan, List<Limit> planLimits) {
		for (Limit limit : planLimits) {
			if (limit.scope() == Scope.GLOBAL) {
				throw new IllegalArgumentException("Plan '" + plan
						+ "' has a GLOBAL limit; the global budget is shared, so set it under limits instead");
			}
		}
	}

	private static List<Limit> merge(List<Limit> limits, List<Limit> planLimits) {
		List<Limit> merged = new ArrayList<>();
		for (Limit limit : limits) {
			merged.add(planLimits.stream().filter(planLimit -> sameScopeAndWindow(planLimit, limit)).findFirst()
				.orElse(limit));
		}
		for (Limit planLimit : planLimits) {
			if (limits.stream().noneMatch(limit -> sameScopeAndWindow(limit, planLimit))) {
				merged.add(planLimit);
			}
		}
		return List.copyOf(merged);
	}

	private static boolean sameScopeAndWindow(Limit a, Limit b) {
		return a.scope() == b.scope() && a.window() == b.window();
	}

	private static String normalise(String plan) {
		return plan.strip().toLowerCase(Locale.ROOT);
	}

	private void warnUnknownPlanOnce(String plan) {
		if (this.unknownPlans.add(plan)) {
			log.warn("Plan '{}' has no limits under token-allowance.plans, so the common limits apply", plan);
		}
	}
}
