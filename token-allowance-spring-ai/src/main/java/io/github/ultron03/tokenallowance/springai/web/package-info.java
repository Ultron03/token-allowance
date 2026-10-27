/**
 * Turns a used-up budget into an HTTP response, so clients get a 429 and know when to retry.
 *
 * <p>Needs {@code spring-web} on the classpath.
 *
 * <p>Everything in this package is non-null unless it is annotated {@code @Nullable}.
 */
@NullMarked
package io.github.ultron03.tokenallowance.springai.web;

import org.jspecify.annotations.NullMarked;
