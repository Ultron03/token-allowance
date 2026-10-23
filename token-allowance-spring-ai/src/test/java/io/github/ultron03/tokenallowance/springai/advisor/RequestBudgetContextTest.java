package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;

class RequestBudgetContextTest {

	@Test
	void countsModelCalls() {
		RequestBudgetContext context = new RequestBudgetContext(Map.of());

		context.recordModelCall();
		int second = context.recordModelCall();

		assertThat(second).isEqualTo(2);
		assertThat(context.modelCalls()).isEqualTo(2);
	}

	@Test
	void addsCost() {
		RequestBudgetContext context = new RequestBudgetContext(Map.of());

		context.addCost(new Money(150));
		context.addCost(new Money(50));

		assertThat(context.cost()).isEqualTo(new Money(200));
	}

	@Test
	void startsWithNoCallsAndNoCost() {
		RequestBudgetContext context = new RequestBudgetContext(Map.of());

		assertThat(context.modelCalls()).isZero();
		assertThat(context.cost()).isEqualTo(Money.ZERO);
	}

	@Test
	void copiesSubjectIds() {
		Map<Scope, String> ids = new EnumMap<>(Scope.class);
		ids.put(Scope.USER, "alice");
		RequestBudgetContext context = new RequestBudgetContext(ids);

		ids.put(Scope.USER, "mallory");

		assertThat(context.subjectIds()).containsExactly(Map.entry(Scope.USER, "alice"));
	}

	@Test
	void fromReturnsContextStoredInRequest() {
		RequestBudgetContext context = new RequestBudgetContext(Map.of());
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt("hi"))
			.context(RequestBudgetContext.CONTEXT_KEY, context)
			.build();

		assertThat(RequestBudgetContext.from(request)).containsSame(context);
	}

	@Test
	void fromReturnsEmptyWhenContextMissing() {
		ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();

		assertThat(RequestBudgetContext.from(request)).isEmpty();
	}
}
