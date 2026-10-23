package io.github.ultron03.tokenallowance.springai.subject;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClientRequest;

import io.github.ultron03.tokenallowance.core.Scope;

/** Finds who a chat request is for; replace or extend it to read ids from your own source. */
@FunctionalInterface
public interface SubjectResolver {

	/** Returns the subject id per scope, or an empty map if this source knows nothing. Called on the request thread. */
	Map<Scope, String> resolve(ChatClientRequest request);
}
