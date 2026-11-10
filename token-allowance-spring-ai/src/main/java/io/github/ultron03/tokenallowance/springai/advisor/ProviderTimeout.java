package io.github.ultron03.tokenallowance.springai.advisor;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Tells whether a failed model call timed out waiting for the answer, so the provider may have
 * done the work and billed it. Every HTTP client throws its own type, so a cause counts when it is
 * an {@link InterruptedIOException} or its class name ends in {@value #TIMEOUT_SUFFIX}. A timeout
 * while connecting doesn't count: the request never reached the provider.
 */
final class ProviderTimeout {

	private static final String TIMEOUT_SUFFIX = "TimeoutException";

	private ProviderTimeout() {
	}

	/** {@return true if {@code failure} or one of its causes is a timeout after the request was sent} */
	static boolean isCauseOf(Throwable failure) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean timedOut = false;
		for (@Nullable Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
			if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
				return false;
			}
			timedOut |= cause instanceof InterruptedIOException
					|| cause.getClass().getSimpleName().endsWith(TIMEOUT_SUFFIX);
		}
		return timedOut;
	}
}
