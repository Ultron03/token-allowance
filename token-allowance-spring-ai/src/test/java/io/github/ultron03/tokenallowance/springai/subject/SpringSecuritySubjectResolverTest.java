package io.github.ultron03.tokenallowance.springai.subject;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.ultron03.tokenallowance.core.Scope;

class SpringSecuritySubjectResolverTest {

	private final SpringSecuritySubjectResolver resolver = new SpringSecuritySubjectResolver();

	private final ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void returnsUserNameWhenAuthenticated() {
		authenticate(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

		assertThat(this.resolver.resolve(this.request)).containsExactly(Map.entry(Scope.USER, "alice"));
	}

	@Test
	void returnsEmptyForAnonymousUser() {
		authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void returnsEmptyWhenNotAuthenticated() {
		authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", null));

		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void returnsEmptyWithoutAuthentication() {
		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	private static void authenticate(Authentication authentication) {
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
