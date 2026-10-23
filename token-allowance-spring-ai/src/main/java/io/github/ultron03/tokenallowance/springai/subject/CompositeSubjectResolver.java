package io.github.ultron03.tokenallowance.springai.subject;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClientRequest;

import io.github.ultron03.tokenallowance.core.Scope;

/** Asks several resolvers in priority order; for each scope the first resolver that finds an id wins. */
public class CompositeSubjectResolver implements SubjectResolver {

	private final List<SubjectResolver> resolvers;

	/** Creates a chain; the list order is the priority order. */
	public CompositeSubjectResolver(List<SubjectResolver> resolvers) {
		Objects.requireNonNull(resolvers, "resolvers must not be null");
		this.resolvers = List.copyOf(resolvers);
	}

	/** Returns the merged ids of all resolvers, keeping the first id found per scope. */
	@Override
	public Map<Scope, String> resolve(ChatClientRequest request) {
		Map<Scope, String> ids = new EnumMap<>(Scope.class);
		for (SubjectResolver resolver : this.resolvers) {
			resolver.resolve(request).forEach(ids::putIfAbsent);
		}
		return Map.copyOf(ids);
	}
}
