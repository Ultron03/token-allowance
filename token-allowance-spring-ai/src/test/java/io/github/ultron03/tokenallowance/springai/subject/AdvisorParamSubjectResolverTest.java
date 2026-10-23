package io.github.ultron03.tokenallowance.springai.subject;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import io.github.ultron03.tokenallowance.core.Scope;

class AdvisorParamSubjectResolverTest {

	private final AdvisorParamSubjectResolver resolver = new AdvisorParamSubjectResolver();

	@Test
	void readsUserTenantAndSessionFromAdvisorParams() {
		ChatClientRequest request = requestWith(Map.of(
				SubjectParams.USER_ID, "alice",
				SubjectParams.TENANT_ID, "acme",
				SubjectParams.SESSION_ID, "s-1"));

		Map<Scope, String> ids = this.resolver.resolve(request);

		assertThat(ids).containsExactlyInAnyOrderEntriesOf(Map.of(
				Scope.USER, "alice",
				Scope.TENANT, "acme",
				Scope.SESSION, "s-1"));
	}

	@Test
	void convertsNonStringIdsWithToString() {
		ChatClientRequest request = requestWith(Map.of(SubjectParams.USER_ID, 42L));

		assertThat(this.resolver.resolve(request)).containsExactly(Map.entry(Scope.USER, "42"));
	}

	@Test
	void stripsWhitespaceAroundId() {
		ChatClientRequest request = requestWith(Map.of(SubjectParams.USER_ID, "  alice "));

		assertThat(this.resolver.resolve(request)).containsExactly(Map.entry(Scope.USER, "alice"));
	}

	@Test
	void ignoresBlankParam() {
		ChatClientRequest request = requestWith(Map.of(
				SubjectParams.USER_ID, "   ",
				SubjectParams.TENANT_ID, "acme"));

		assertThat(this.resolver.resolve(request)).containsExactly(Map.entry(Scope.TENANT, "acme"));
	}

	@Test
	void returnsEmptyWhenNoParamIsSet() {
		ChatClientRequest request = requestWith(Map.of("some-other-advisor.key", "value"));

		assertThat(this.resolver.resolve(request)).isEmpty();
	}

	private static ChatClientRequest requestWith(Map<String, Object> context) {
		return ChatClientRequest.builder().prompt(new Prompt("hi")).context(context).build();
	}
}
