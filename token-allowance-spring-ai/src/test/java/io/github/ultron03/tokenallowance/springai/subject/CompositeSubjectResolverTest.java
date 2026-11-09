package io.github.ultron03.tokenallowance.springai.subject;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import io.github.ultron03.tokenallowance.core.Scope;

class CompositeSubjectResolverTest {

	private final ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();

	@Test
	void firstResolverWinsForTheSameScope() {
		CompositeSubjectResolver composite = new CompositeSubjectResolver(List.of(
				request -> Map.of(Scope.USER, "from-param"),
				request -> Map.of(Scope.USER, "from-header")));

		assertThat(composite.resolve(this.request)).containsExactly(Map.entry(Scope.USER, "from-param"));
	}

	@Test
	void mergesDifferentScopesFromDifferentResolvers() {
		CompositeSubjectResolver composite = new CompositeSubjectResolver(List.of(
				request -> Map.of(Scope.TENANT, "acme"),
				request -> Map.of(Scope.USER, "alice")));

		assertThat(composite.resolve(this.request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				Scope.TENANT, "acme",
				Scope.USER, "alice"));
	}

	@Test
	void firstResolverThatKnowsThePlanWins() {
		CompositeSubjectResolver composite = new CompositeSubjectResolver(List.of(
				request -> Map.of(),
				planResolver("pro"),
				planResolver("free")));

		assertThat(composite.resolvePlan(this.request)).isEqualTo("pro");
	}

	@Test
	void returnsEmptyWhenNoResolverFindsAnything() {
		CompositeSubjectResolver composite = new CompositeSubjectResolver(List.of(
				request -> Map.of(),
				request -> Map.of()));

		assertThat(composite.resolve(this.request)).isEmpty();
	}

	private static SubjectResolver planResolver(String plan) {
		return new SubjectResolver() {

			@Override
			public Map<Scope, String> resolve(ChatClientRequest request) {
				return Map.of();
			}

			@Override
			public String resolvePlan(ChatClientRequest request) {
				return plan;
			}
		};
	}
}
