package io.github.ultron03.tokenallowance.springai.spike;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

class ModelSwitchingAdvisor implements CallAdvisor {

	private final String newModelName;

	public ModelSwitchingAdvisor(String newModelName) {
		this.newModelName = newModelName;
	}

	@Override
	public String getName() {
		return "model-switching";
	}

	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER - 1;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		ChatOptions originalOptions = chatClientRequest.prompt().getOptions();
		ChatOptions newOptions = originalOptions.mutate().model(this.newModelName).build();
		Prompt newPrompt = chatClientRequest.prompt().mutate().chatOptions(newOptions).build();
		ChatClientRequest newRequest = chatClientRequest.mutate().prompt(newPrompt).build();
		return callAdvisorChain.nextCall(newRequest);
	}
}
