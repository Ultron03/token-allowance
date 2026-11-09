package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CallerTest {

	@Test
	void blankPlanMeansNoPlan() {
		assertThat(new Caller(Map.of(), " ").plan()).isNull();
	}

	@Test
	void copiesSubjectIds() {
		Map<Scope, String> ids = new EnumMap<>(Scope.class);
		ids.put(Scope.USER, "alice");
		Caller caller = new Caller(ids, null);

		ids.put(Scope.USER, "mallory");

		assertThat(caller.subjectIds()).containsExactly(Map.entry(Scope.USER, "alice"));
	}

	@Test
	void stripsPlan() {
		assertThat(new Caller(Map.of(), " pro ").plan()).isEqualTo("pro");
	}
}
