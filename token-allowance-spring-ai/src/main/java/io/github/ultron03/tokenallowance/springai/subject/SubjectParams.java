package io.github.ultron03.tokenallowance.springai.subject;

/** Advisor param keys an app sets per call, e.g. {@code .advisors(a -> a.param(SubjectParams.USER_ID, "alice"))}. */
public final class SubjectParams {

	/** Key for the user id. */
	public static final String USER_ID = "token-allowance.user-id";

	/** Key for the tenant id. */
	public static final String TENANT_ID = "token-allowance.tenant-id";

	/** Key for the session id. */
	public static final String SESSION_ID = "token-allowance.session-id";

	private SubjectParams() {
	}
}
