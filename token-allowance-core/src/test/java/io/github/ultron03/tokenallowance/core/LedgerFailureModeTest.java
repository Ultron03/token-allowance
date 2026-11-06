package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerFailureModeTest {

	@Test
	void defaultsToFailOpenInObserveAndFailClosedInEnforce() {
		assertThat(LedgerFailureMode.defaultFor(EnforcementMode.OBSERVE)).isEqualTo(LedgerFailureMode.FAIL_OPEN);
		assertThat(LedgerFailureMode.defaultFor(EnforcementMode.ENFORCE)).isEqualTo(LedgerFailureMode.FAIL_CLOSED);
	}
}
