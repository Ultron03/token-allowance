# Contributing

Bug reports, questions and pull requests are welcome. For anything bigger than a small fix, open an issue first, so we can agree on the approach before you write the code.

## Building

You need JDK 17 or later. Docker is optional: without it, the Redis tests are skipped.

```bash
./mvnw verify
```

Tests never call a real model or a paid API. Model calls go to `FakeChatModel`, a scripted fake in the `token-allowance-spring-ai` test sources, and Redis tests start their own container with Testcontainers.

## Modules

| Module | What goes in it |
|---|---|
| `token-allowance-core` | Budget rules, prices, the ledger port. Plain Java: no Spring, Reactor or Redis imports. |
| `token-allowance-spring-ai` | The advisors and the 429/503 handler. They translate between Spring AI and core; they make no budget decisions. |
| `token-allowance-redis` | The Redis ledger and its Lua scripts. |
| `token-allowance-autoconfigure` | Properties and bean wiring only. |
| `token-allowance-spring-boot-starter` | Dependencies only, no code. |

## Code style

- Java 17 language level, tabs for indentation.
- Records for value objects, validated in the compact constructor.
- Money is never a `double`: use `Money` (whole micro-dollars).
- Constructor injection; every auto-configured bean is `@ConditionalOnMissingBean`.
- Public types and methods get a short Javadoc that says why, not what.
- Never log prompt or response text. Subject ids only at DEBUG. Metrics are never tagged with a subject id.

## Tests

JUnit 5 and AssertJ. Name a test after the behaviour it checks, for example `deniesRequestWhenDailyCostLimitExceeded`. Add one test per behaviour your change could break, and prefer real objects and small fakes over mocks.

## Pull requests

- One change per pull request, with tests.
- `./mvnw verify` passes.
- If the change affects users, update the README.
- By contributing, you agree that your contribution is licensed under the Apache License 2.0.
