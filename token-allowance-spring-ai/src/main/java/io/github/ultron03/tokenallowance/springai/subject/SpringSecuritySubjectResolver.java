package io.github.ultron03.tokenallowance.springai.subject;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.ultron03.tokenallowance.core.Scope;

/** Uses the logged-in user's name as the user id; anonymous visitors get no id, so they don't share one budget. */
public class SpringSecuritySubjectResolver implements SubjectResolver {

	/** Returns the user id, or an empty map if nobody is logged in on this thread. */
	@Override
	public Map<Scope, String> resolve(ChatClientRequest request) {
		@Nullable Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return Map.of();
		}
		String name = auth.getName().strip();
		if (name.isEmpty()) {
			return Map.of();
		}
		return Map.of(Scope.USER, name);
	}
}
