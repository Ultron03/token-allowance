package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConservativeTokenEstimatorTest {

	private static final ConservativeTokenEstimator ESTIMATOR = new ConservativeTokenEstimator();

	@Test
	void estimatesZeroTokensForEmptyText() {
		long tokens = ESTIMATOR.estimateInputTokens("");

		assertThat(tokens).isEqualTo(0);
	}

	@Test
	void countsThreeCharactersAsOneToken() {
		long tokens = ESTIMATOR.estimateInputTokens("abc");

		assertThat(tokens).isEqualTo(1);
	}

	@Test
	void roundsAPartialTokenUp() {
		long tokens = ESTIMATOR.estimateInputTokens("abcd");

		assertThat(tokens).isEqualTo(2);
	}

	@Test
	void roundsASingleCharacterUpToOneToken() {
		long tokens = ESTIMATOR.estimateInputTokens("a");

		assertThat(tokens).isEqualTo(1);
	}

	@Test
	void estimatesLongText() {
		long tokens = ESTIMATOR.estimateInputTokens("a".repeat(3000));

		assertThat(tokens).isEqualTo(1000);
	}

	@Test
	void rejectsNullText() {
		assertThatThrownBy(() -> ESTIMATOR.estimateInputTokens(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void reservesMaxTokensForOutputByDefault() {
		assertThat(ESTIMATOR.estimateOutputTokens(200)).isEqualTo(200);
	}

	@Test
	void reservesDefaultOutputTokensWithoutMaxTokens() {
		assertThat(ESTIMATOR.estimateOutputTokens(null)).isEqualTo(TokenEstimator.DEFAULT_OUTPUT_TOKENS);
	}

	@Test
	void reservesExpectedOutputWhenBelowMaxTokens() {
		assertThat(new ConservativeTokenEstimator(60).estimateOutputTokens(200)).isEqualTo(60);
	}

	@Test
	void neverReservesMoreOutputThanMaxTokens() {
		assertThat(new ConservativeTokenEstimator(60).estimateOutputTokens(40)).isEqualTo(40);
	}

	@Test
	void reservesExpectedOutputWithoutMaxTokens() {
		assertThat(new ConservativeTokenEstimator(60).estimateOutputTokens(null)).isEqualTo(60);
	}

	@Test
	void rejectsExpectedOutputOfZero() {
		assertThatThrownBy(() -> new ConservativeTokenEstimator(0)).isInstanceOf(IllegalArgumentException.class);
	}
}
