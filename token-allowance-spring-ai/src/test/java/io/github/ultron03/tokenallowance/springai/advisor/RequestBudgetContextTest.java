package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import io.github.ultron03.tokenallowance.core.Caller;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.RequestLimit;

class RequestBudgetContextTest {

	private static final Caller NOBODY = new Caller(Map.of(), null);

	@Test
	void countsModelCalls() {
		RequestBudgetContext context = new RequestBudgetContext(NOBODY, RequestLimit.NONE);

		context.recordModelCall();
		int second = context.recordModelCall();

		assertThat(second).isEqualTo(2);
		assertThat(context.modelCalls()).isEqualTo(2);
	}

	@Test
	void addsCost() {
		RequestBudgetContext context = new RequestBudgetContext(NOBODY, RequestLimit.NONE);

		context.addCost(new Money(150));
		context.addCost(new Money(50));

		assertThat(context.cost()).isEqualTo(new Money(200));
	}

	@Test
	void startsWithNoCallsAndNoCost() {
		RequestBudgetContext context = new RequestBudgetContext(NOBODY, RequestLimit.NONE);

		assertThat(context.modelCalls()).isZero();
		assertThat(context.cost()).isEqualTo(Money.ZERO);
	}

	@Test
	void fromReturnsContextStoredInRequest() {
		RequestBudgetContext context = new RequestBudgetContext(NOBODY, RequestLimit.NONE);
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
