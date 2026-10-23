/**
 * Finds who a chat request is for, so the budget service knows which user, tenant and session
 * limits apply.
 *
 * <p>Resolvers read advisor params, Spring Security or an HTTP header and are combined in
 * priority order by {@link io.github.ultron03.tokenallowance.springai.subject.CompositeSubjectResolver}.
 *
 * <p>Everything in this package is non-null unless it is annotated {@code @Nullable}.
 */
@NullMarked
package io.github.ultron03.tokenallowance.springai.subject;

import org.jspecify.annotations.NullMarked;
