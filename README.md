# Token Allowance

A Spring Boot starter that enforces per-user, per-tenant and per-session token and dollar budgets on Spring AI `ChatClient` calls.

Work in progress, not released yet.

## Limitations

- **The pre-call token estimate is approximate.** Before a call, the default estimator assumes one token per three characters, rounded up. For English and most code this is higher than the real count, which is the safe direction. For Chinese, Japanese and Korean text, where a single character is often one token or more, it can be about three times too low. After the call, the token count reported by the provider replaces the estimate. The estimate is charged only when the provider reports no usage. To get exact counts, register your own `TokenEstimator` bean.
