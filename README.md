# Token Allowance

A Spring Boot starter that enforces per-user, per-tenant and per-session token and dollar budgets on Spring AI `ChatClient` calls.

Work in progress, not released yet.

## Limitations

- **The pre-call token estimate is approximate.** Before a call, the default estimator assumes one token per three characters, rounded up. For English and most code this is higher than the real count, which is the safe direction. For Chinese, Japanese and Korean text, where a single character is often one token or more, it can be about three times too low. After the call, the token count reported by the provider replaces the estimate. The estimate is charged only when the provider reports no usage. To get exact counts, register your own `TokenEstimator` bean.
- **A denied stream logs an ERROR line.** When a budget denies a streamed call, Spring AI's `MessageAggregator` logs `Aggregation Error` with the `BudgetExceededException`, even when your app handles the exception. The log line comes from Spring AI and can be ignored.
- **A stream can be subscribed only once.** Spring AI runs its advisor chain once per `stream()` call. Subscribing twice to the same result fails instead of making a second call; call `stream()` again for a new request.
- **A streamed response that has started can't become a 429.** If a budget runs out during a tool loop after the first chunk was sent, the HTTP status is already fixed, so the error ends the stream instead.
