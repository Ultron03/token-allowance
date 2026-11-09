package io.github.ultron03.tokenallowance.springai.subject;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Uses the logged-in user's name as the user id, and a {@code PLAN_<name>} authority as the plan.
 * Anonymous visitors get no id, so they don't share one budget.
 */
public class SpringSecuritySubjectResolver implements SubjectResolver {

	/** Authorities starting with this name the caller's plan, for example {@code PLAN_pro}. */
	public static final String PLAN_AUTHORITY_PREFIX = "PLAN_";

	/** Returns the user id, or an empty map if nobody is logged in on this thread. */
	@Override
	public Map<Scope, String> resolve(ChatClientRequest request) {
		@Nullable Authentication auth = loggedIn();
		if (auth == null) {
			return Map.of();
		}
		String name = auth.getName().strip();
		if (name.isEmpty()) {
			return Map.of();
		}
		return Map.of(Scope.USER, name);
	}

	/** Returns the name after {@link #PLAN_AUTHORITY_PREFIX} of the first plan authority, or null if there is none. */
	@Override
	public @Nullable String resolvePlan(ChatClientRequest request) {
		@Nullable Authentication auth = loggedIn();
		if (auth == null) {
			return null;
		}
		for (GrantedAuthority authority : auth.getAuthorities()) {
			@Nullable String name = authority.getAuthority();
			if (name != null && name.startsWith(PLAN_AUTHORITY_PREFIX)
					&& name.length() > PLAN_AUTHORITY_PREFIX.length()) {
				return name.substring(PLAN_AUTHORITY_PREFIX.length());
			}
		}
		return null;
	}

	private static @Nullable Authentication loggedIn() {
		@Nullable Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return auth;
	}
}
