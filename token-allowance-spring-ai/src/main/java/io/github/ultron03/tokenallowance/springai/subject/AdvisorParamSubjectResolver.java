package io.github.ultron03.tokenallowance.springai.subject;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;

import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Reads ids from the {@link SubjectParams} advisor params; works in streams and background jobs too.
 * Without a session id param, chat memory's {@link ChatMemory#CONVERSATION_ID} is the session id.
 */
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
			@Nullable String id = paramOf(request, entry.getKey());
			if (id != null) {
				ids.put(entry.getValue(), id);
			}
		}
		@Nullable String conversationId = paramOf(request, ChatMemory.CONVERSATION_ID);
		if (conversationId != null) {
			ids.putIfAbsent(Scope.SESSION, conversationId);
		}
		return Map.copyOf(ids);
	}

	/** Returns the {@link SubjectParams#PLAN} param, or null if it is not set or blank. */
	@Override
	public @Nullable String resolvePlan(ChatClientRequest request) {
		return paramOf(request, SubjectParams.PLAN);
	}

	private static @Nullable String paramOf(ChatClientRequest request, String key) {
		@Nullable Object value = request.context().get(key);
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		return value.toString().strip();
	}
}
