package io.github.ultron03.tokenallowance.springai.subject;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;

import io.github.ultron03.tokenallowance.core.Scope;

/** Reads ids from the {@link SubjectParams} advisor params; works in streams and background jobs too. */
public class AdvisorParamSubjectResolver implements SubjectResolver {

	private static final Map<String, Scope> SCOPES_BY_PARAM = Map.of(
			SubjectParams.USER_ID, Scope.USER,
			SubjectParams.TENANT_ID, Scope.TENANT,
			SubjectParams.SESSION_ID, Scope.SESSION);

	/** Returns every param that is set and not blank; non-string values are converted with {@code toString()}. */
	@Override
	public Map<Scope, String> resolve(ChatClientRequest request) {
		Map<Scope, String> ids = new EnumMap<>(Scope.class);
		for (Map.Entry<String, Scope> entry : SCOPES_BY_PARAM.entrySet()) {
			@Nullable Object value = request.context().get(entry.getKey());
			if (value == null) {
				continue;
			}
			String id = value.toString().strip();
			if (!id.isEmpty()) {
				ids.put(entry.getValue(), id);
			}
		}
		return Map.copyOf(ids);
	}
}
