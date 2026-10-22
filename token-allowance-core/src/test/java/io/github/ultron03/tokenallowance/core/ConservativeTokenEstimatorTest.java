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
}
