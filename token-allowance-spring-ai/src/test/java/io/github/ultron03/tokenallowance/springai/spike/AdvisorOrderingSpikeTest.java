package io.github.ultron03.tokenallowance.springai.spike;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import io.github.ultron03.tokenallowance.springai.fixture.FakeChatModel;

public class AdvisorOrderingSpikeTest {

	private static final Prompt ANY_PROMPT = new Prompt("What time is it?");
	
	private static class CountingAdvisor implements CallAdvisor {
		private final String name;
		private final int order;
		private int counter;

		public CountingAdvisor (String name, int order) {
			this.name = name;
			this.order = order;
		}

		@Override 
		public String getName() {
			return this.name;
		}

		@Override 
		public int getOrder() {
			return this.order;
		}

		public int count() {
			return this.counter;
		}

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			this.counter++;
			return chain.nextCall(request);
		}
	}

	private final CountingAdvisor beforeAdvisor = new CountingAdvisor("before", ToolCallingAdvisor.DEFAULT_ORDER - 1);
	private final CountingAdvisor afterAdvisor = new CountingAdvisor("after", ToolCallingAdvisor.DEFAULT_ORDER + 1);

	public static class TimeTool {
		@Tool(name = FakeChatModel.TIME_TOOL_NAME, description = "Returns the current time")
		public String getCurrentTime() {
			return "12:00 PM";
		}
	}

	@Test
	public void advisorBeforeToolCallingRunsOncePerRequestAndAdvisorAfterRunsOncePerModelCall() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();
		TimeTool timeTool = new TimeTool();

		ChatClient chatClient = ChatClient.builder(model).build();

		chatClient.prompt(ANY_PROMPT)
			.advisors(beforeAdvisor, afterAdvisor)
			.tools(timeTool)
			.call().content();

		assertThat(beforeAdvisor.count()).isEqualTo(1);
		assertThat(afterAdvisor.count()).isEqualTo(2);
		assertThat(model.callCount()).isEqualTo(2);
	}
}
