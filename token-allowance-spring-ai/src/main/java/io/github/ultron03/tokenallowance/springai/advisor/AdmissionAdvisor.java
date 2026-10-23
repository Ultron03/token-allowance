package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;

import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.Decision;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.springai.BudgetExceededException;
import io.github.ultron03.tokenallowance.springai.subject.SubjectParams;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;
import reactor.core.publisher.Flux;

/**
 * Checks once per request, before chat memory and the tool loop, whether the caller's budget is
 * already used up, and stores a {@link RequestBudgetContext} for the per-call advisors.
 *
 * <p>The subject is resolved on the thread that calls or subscribes. Spring Security and header
 * resolvers only work on servlet threads, so WebFlux apps must pass the id as an advisor param.
 */
public class AdmissionAdvisor implements CallAdvisor, StreamAdvisor {

	/** Runs before chat memory ({@code HIGHEST_PRECEDENCE + 200}) and tool calling ({@code + 300}). */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

	private static final Logger log = LoggerFactory.getLogger(AdmissionAdvisor.class);

	private final SubjectResolver subjectResolver;

	private final BudgetService budgetService;

	private final AtomicBoolean warnedNoSubject = new AtomicBoolean();

	/** Creates the advisor. */
	public AdmissionAdvisor(SubjectResolver subjectResolver, BudgetService budgetService) {
		this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver can't be null");
		this.budgetService = Objects.requireNonNull(budgetService, "budgetService can't be null");
	}

	@Override
	public String getName() {
		return "TokenAllowanceAdmissionAdvisor";
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	/** @throws BudgetExceededException if a budget is used up; the model is not called */
	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(admit(request));
	}

	/** Admits on the subscribing thread; a used-up budget ends the stream with {@link BudgetExceededException}. */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		ChatClientRequest admitted;
		try {
			admitted = admit(request);
		}
		catch (BudgetExceededException ex) {
			return Flux.error(ex);
		}
		return chain.nextStream(admitted);
	}

	private ChatClientRequest admit(ChatClientRequest request) {
		Map<Scope, String> subjectIds = this.subjectResolver.resolve(request);
		if (subjectIds.isEmpty()) {
			warnNoSubjectOnce();
		}
		Decision decision = this.budgetService.admit(subjectIds);
		if (decision instanceof Decision.Denied denied) {
			throw new BudgetExceededException(denied);
		}
		return request.mutate()
			.context(RequestBudgetContext.CONTEXT_KEY, new RequestBudgetContext(subjectIds))
			.build();
	}

	private void warnNoSubjectOnce() {
		if (this.warnedNoSubject.compareAndSet(false, true)) {
			log.warn("No subject found for a chat request, so only global limits apply. "
					+ "Set the {} advisor param, or log users in with Spring Security.",
					SubjectParams.USER_ID);
		}
		else {
			log.debug("No subject found for a chat request, only global limits apply");
		}
	}
}
