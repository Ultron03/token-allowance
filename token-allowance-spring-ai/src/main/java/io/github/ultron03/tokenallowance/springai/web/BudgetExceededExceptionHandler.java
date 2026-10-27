package io.github.ultron03.tokenallowance.springai.web;

import java.time.Duration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.ultron03.tokenallowance.springai.BudgetExceededException;

/**
 * Answers a {@link BudgetExceededException} with 429 Too Many Requests, a {@link ProblemDetail} body
 * and a {@code Retry-After} header in seconds.
 *
 * <p>Ordered first, so an app's catch-all {@code @ExceptionHandler(Exception.class)} doesn't turn a
 * used-up budget into a 500. Apps that want their own response leave this handler out.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BudgetExceededExceptionHandler {

	/** Problem title shared by every budget denial; the detail says which budget. */
	public static final String TITLE = "Token budget exceeded";

	/** Returns the 429; {@code Retry-After} is left out when the client may retry at once. */
	@ExceptionHandler(BudgetExceededException.class)
	public ResponseEntity<ProblemDetail> handle(BudgetExceededException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				ex.decision().reason());
		problem.setTitle(TITLE);

		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
		Duration retryAfter = ex.decision().retryAfter();
		if (!retryAfter.isZero()) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(wholeSecondsRoundedUp(retryAfter)));
		}
		return response.body(problem);
	}

	/** Rounds up, so a client that waits exactly this long finds the window reset. */
	private static long wholeSecondsRoundedUp(Duration duration) {
		long seconds = duration.getSeconds();
		return (duration.getNano() > 0) ? seconds + 1 : seconds;
	}
}
