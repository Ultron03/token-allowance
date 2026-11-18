# Changelog

## 0.3.2 (2026-11-18)

- Fixed: with Spring AI 2.1.0-RC2 or later, Anthropic calls that used prompt caching charged the cached input tokens twice.
- Fixed: Bedrock Converse calls that used prompt caching were undercharged, because cache reads and writes were not counted as input.
- CI now also builds against the oldest supported Spring AI (2.0.0) and the newest one, every week.

## 0.3.1 (2026-11-11)

- The user id header is opt-in: `subject.header` has no default any more. Set it if you relied on `X-User-Id`, and only behind a gateway that sets it.
- A call that times out waiting for the answer is charged like an interrupted stream (`stream.interrupted`), since the provider may have billed it.
- Gemini thinking and tool-use tokens are charged as output.
- A reservation that is never settled, for example on a crashed instance, is given back after an hour.
- A `BudgetListener` that throws no longer fails the call.
- The input estimate counts tool definitions and reserves 1,600 tokens per image or other media item.

## 0.3.0 (2026-11-10)

- Micrometer metrics, the `tokenallowance` actuator endpoint, and Spring events when a budget reaches a threshold (80% and 100% by default) and when a call is charged.
- Plans: different limits for free and paid users (`token-allowance.plans`), or limits from your own code through a `LimitResolver`.
- `MINUTE` and `HOUR` windows for burst limits.
- Per-endpoint request caps through advisor parameters.
- Top up or reset a subject's budget.
- `estimate.output-tokens` reserves a typical answer length instead of the longest possible one.
- Breaking for custom `UsageLedger` implementations: `commit` returns the committed amounts, and `refund` was added.

## 0.2.0 (2026-11-07)

- Redis ledger: budgets shared across instances (`store.type: redis`), each change one atomic Lua script.
- `store.failure-mode` decides what happens while Redis is down: let calls through, or refuse them with a 503.

## 0.1.1 (2026-10-31)

- Fixed: the application failed to start without `spring-boot-starter-validation`. The starter now brings it.

## 0.1.0 (2026-10-31)

- First release: per-user, per-tenant and global token and cost budgets for Spring AI `ChatClient` calls, daily and monthly windows, per-request caps on model calls and cost, 429 responses with `Retry-After`, an in-memory ledger and Spring Boot auto-configuration.
