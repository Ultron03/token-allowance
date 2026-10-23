package io.github.ultron03.tokenallowance.springai.subject;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Reads the user id from an HTTP header of the current servlet request.
 *
 * <p>The caller controls the header and can send any id, or a new one per call to get a fresh
 * budget each time. Only use this behind a gateway that sets the header itself.
 */
public class HeaderSubjectResolver implements SubjectResolver {

	/** Longer ids are ignored, so a client can't fill ledger memory with huge keys. */
	static final int MAX_ID_LENGTH = 128;

	private final String headerName;

	/** Creates a resolver for the given header, e.g. {@code X-User-Id}. */
	public HeaderSubjectResolver(String headerName) {
		Objects.requireNonNull(headerName, "headerName must not be null");
		if (headerName.isBlank()) {
			throw new IllegalArgumentException("headerName must not be blank");
		}
		this.headerName = headerName;
	}

	/** Returns the user id, or an empty map outside a servlet request or if the header is missing, blank or too long. */
	@Override
	public Map<Scope, String> resolve(ChatClientRequest request) {
		@Nullable RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servlet)) {
			return Map.of();
		}
		@Nullable String value = servlet.getRequest().getHeader(this.headerName);
		if (value == null) {
			return Map.of();
		}
		String id = value.strip();
		if (id.isEmpty() || id.length() > MAX_ID_LENGTH) {
			return Map.of();
		}
		return Map.of(Scope.USER, id);
	}
}
