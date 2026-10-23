package io.github.ultron03.tokenallowance.springai.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.github.ultron03.tokenallowance.core.Scope;

class HeaderSubjectResolverTest {

	private static final String HEADER = "X-User-Id";

	private final HeaderSubjectResolver resolver = new HeaderSubjectResolver(HEADER);

	private final ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();

	@AfterEach
	void resetRequestAttributes() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void readsUserIdFromHeader() {
		sendHeader("alice");

		assertThat(this.resolver.resolve(this.request)).containsExactly(Map.entry(Scope.USER, "alice"));
	}

	@Test
	void returnsEmptyWhenHeaderMissing() {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void ignoresBlankHeaderValue() {
		sendHeader("   ");

		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void acceptsIdOfMaxLength() {
		String id = "a".repeat(HeaderSubjectResolver.MAX_ID_LENGTH);
		sendHeader(id);

		assertThat(this.resolver.resolve(this.request)).containsExactly(Map.entry(Scope.USER, id));
	}

	@Test
	void ignoresIdLongerThanMaxLength() {
		sendHeader("a".repeat(HeaderSubjectResolver.MAX_ID_LENGTH + 1));

		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void returnsEmptyOutsideARequest() {
		assertThat(this.resolver.resolve(this.request)).isEmpty();
	}

	@Test
	void rejectsBlankHeaderName() {
		assertThatThrownBy(() -> new HeaderSubjectResolver(" "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static void sendHeader(String value) {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest();
		servletRequest.addHeader(HEADER, value);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
	}
}
