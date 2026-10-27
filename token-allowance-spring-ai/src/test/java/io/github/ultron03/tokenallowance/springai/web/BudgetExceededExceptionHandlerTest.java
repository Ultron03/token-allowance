package io.github.ultron03.tokenallowance.springai.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.github.ultron03.tokenallowance.core.Decision;
import io.github.ultron03.tokenallowance.springai.BudgetExceededException;

class BudgetExceededExceptionHandlerTest {

	private final BudgetExceededExceptionHandler handler = new BudgetExceededExceptionHandler();

	@Test
	void answersWithTooManyRequestsAndReasonAsDetail() {
		ResponseEntity<ProblemDetail> response = handle("daily USER budget used up", Duration.ofHours(1));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		ProblemDetail problem = response.getBody();
		assertThat(problem).isNotNull();
		assertThat(problem.getStatus()).isEqualTo(429);
		assertThat(problem.getTitle()).isEqualTo(BudgetExceededExceptionHandler.TITLE);
		assertThat(problem.getDetail()).isEqualTo("daily USER budget used up");
	}

	@Test
	void setsRetryAfterInSeconds() {
		ResponseEntity<ProblemDetail> response = handle("used up", Duration.ofMinutes(5));

		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
	}

	@Test
	void roundsRetryAfterUpToWholeSeconds() {
		ResponseEntity<ProblemDetail> response = handle("used up", Duration.ofMillis(1_500));

		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
	}

	@Test
	void leavesOutRetryAfterWhenClientMayRetryAtOnce() {
		ResponseEntity<ProblemDetail> response = handle("request cost limit reached", Duration.ZERO);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
	}

	private ResponseEntity<ProblemDetail> handle(String reason, Duration retryAfter) {
		return this.handler.handle(new BudgetExceededException(new Decision.Denied(reason, retryAfter)));
	}
}
