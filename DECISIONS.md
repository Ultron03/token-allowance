# Design Decisions

## 1. Budget advisor runs before ToolCallingAdvisor (2026-10-10)

**Context:** 
With tools, one request can mean several model calls, and ToolCallingAdvisor loops over the advisors placed after it.

**Experiment (AdvisorOrderingSpikeTest):** 
Two counting advisors at DEFAULT_ORDER - 1 and + 1, with the fake model doing a tool call and then an answer (Spring AI 2.0.1).

**Result:** 
before = 1, after = 2, model = 2.

**Decision:**
The budget advisor gets an order lower than ToolCallingAdvisor.DEFAULT_ORDER. It checks the budget once per request and records the total usage once.

**Consequence:**
Re-run the spike after upgrading Spring AI, because this behavior belongs to the library and could change.

## 2. Test Usage Report on Normal Call, Stream, and When Stream Usage is Off (2026-10-10)

**Context**
In streaming, usage may come only at the end or not at all, and counting missing usage as 0 would let calls go uncharged.

**Experiment (UsageReportingSpikeTest)**
- the five cases: the Ollama native client, and the OpenAI client pointed at Ollama's /v1
- usage logged per chunk
- versions: Spring AI 2.0.1, Ollama 0.20.2, llama3.2


**Result**
In a stream, usage arrives only on the last chunk; a normal call carries it on its single response.

| # | Client | Mode | Chunks | Where the usage was (prompt / completion) | Usage type |
|---|---|---|---|---|---|
| 1 | Ollama native | call | 1 | 31 / 7 | `DefaultUsage` |
| 2 | Ollama native | stream | 8 | last chunk only (31 / 8); chunks 0–6 are 0 / 0 | `DefaultUsage` |
| 3 | OpenAI-compatible (Ollama `/v1`) | call | 1 | 31 / 8 | `DefaultUsage` |
| 4 | OpenAI-compatible (Ollama `/v1`) | stream, default | 9 | last chunk only (31 / 8); chunks 0–7 are 0 / 0 | `DefaultUsage` |
| 5 | OpenAI-compatible (Ollama `/v1`) | stream, `streamUsage(false)` | 7 | nowhere; every chunk is 0 / 0 | `DefaultUsage` |

Cached-token fields were `null` in every case.

*Missing usage arrives as a DefaultUsage with 0 tokens, not as EmptyUsage, so the library detects missing usage by zero tokens, not by type.*

**Decision**
We will charge the Estimate when tokens are 0, because user might stop streaming of usage but that doesn't mean they are not getting charged and we need to Warn and Limit them from Using excess token.

**Consequence**
The Estimation might not be close to real consumption and User might get over the limit.

## 3. Model downgrade is feasible with a generic advisor (2026-10-10)

**Context**
A possible later feature is switching a user to a cheaper model when they are close to their budget.
That needs an advisor to change the model name in the request, using only the shared `ChatOptions`
interface, because the library must work with every provider.

**Experiment (ModelSwitchingSpikeTest, ModelSwitchingAdvisor)**
- The advisor copies the request with `mutate()` (options → prompt → request), sets a new model name
  and passes the copy on. It is ordered before `ToolCallingAdvisor`.
- Fake model with a tool call and an answer: check the model name each of the two model calls received.
- Ollama: a normal call without the advisor works; with the advisor switching to a model that is not
  installed, Ollama must reject it.
- Versions: Spring AI 2.0.1, Ollama 0.20.2, `llama3.2`.

**Result**
| Question | Answer | Evidence |
|---|---|---|
| Can an advisor change the model through `ChatOptions` alone? | Yes | both model calls received `cheaper-model` |
| Is the provider-specific options type kept? | Yes | the tool loop still ran twice, which only happens with `ToolCallingChatOptions` |
| Does the provider use the new name? | Yes | Ollama answered `model 'llama3.1' not found` |

**Decision**
Model downgrade is technically possible with a generic advisor. It stays a stretch feature and is not
part of the first release.

**Consequence**
- With Azure OpenAI, a configured deployment name takes priority over the model name, so a switch
  would be silently ignored there. This must be documented if the feature is built.
- The downgrade advisor must run before `ToolCallingAdvisor`, so every round of a tool loop uses the
  cheaper model.

## 4. Money is stored as whole micro-dollars (2026-10-11)

**Context**
Per-token prices are tiny (for example $0.0000025), and limits are compared on every call. `double`
adds rounding error, and Redis can only add integers atomically.

**Decision**
- `Money` is a non-negative `long` of micro-dollars (1 USD = 1,000,000).
- `Money.ofDollars("1.50")` takes a string and rejects more than six decimals instead of rounding.
- A call's cost is computed exactly and rounded up to a whole micro-dollar **once**, on the call's
  total (`ModelPrice.costOf`), not once per part.

**Consequence**
- Limit checks, Redis `INCRBY` and tests are exact.
- A call is charged at most 1 micro-dollar above its exact price, which is negligible next to
  out-of-date prices.
- Overflow throws `ArithmeticException` instead of wrapping around.

## 5. Input tokens always include cached tokens (2026-10-11)

**Context**
OpenAI's `prompt_tokens` includes cached tokens. Anthropic's `input_tokens` counts only the tokens
after the last cache breakpoint and reports cache reads and writes separately.

**Decision**
`TokenUsage.input` is always all input tokens. `cachedInput` (cache reads) and `cacheWriteInput`
(cache writes) are parts of it, so `total() = input + output`. The Spring AI adapter adds Anthropic's
parts together before building a `TokenUsage`.

**Consequence**
- One cost formula for every provider. Cache prices that are not configured fall back to the input price.
- If a provider's usage is not normalised, validation fails (cached > input) instead of charging a
  wrong amount.

## 6. Prices come from configuration and model names match strictly (2026-10-18)

**Context**
No provider API returns prices, and prices change. A loose match, such as pricing `gpt-4o-mini` as
`gpt-4o`, would be off by a large factor without anyone noticing.

**Decision**
- In v0.1, prices come only from user configuration, per million tokens, the unit used on provider
  pricing pages.
- Lookup: exact name ignoring case, then the name without a trailing date (`-2024-08-06`,
  `-20250929`), then the unknown-model policy. No prefix matching.
- Unknown-model policy: `ALLOW_UNCOSTED` returns no price (tokens still count), `DEFAULT_PRICE`
  returns the default price, `REJECT` throws `UnknownModelException`. Each unknown model is logged
  as a WARN once.

**Consequence**
- Every model in use must be configured; the WARN names the missing one.
- A bundled price list is a later idea (see `IDEAS.md`).

## 7. The token estimate is characters / 3, rounded up (2026-10-18)

**Context**
Before a call there is only the prompt text, tokenizers differ by provider, and the estimate is only
held until the real usage arrives.

**Decision**
`ConservativeTokenEstimator` returns `(chars + 2) / 3` (`Math.ceilDiv` needs Java 18; we compile for
17). `String.length()` counts UTF-16 units, so an emoji counts twice, which is the safe direction.

**Consequence**
English text is overestimated, which is safe. CJK text is underestimated about 3x; this is listed in
the README Limitations. Users who need exact counts can replace the `TokenEstimator` bean.

## 8. Budget keys and limits (2026-10-11)

**Context**
Every call in the same window must find the same counter, and configuration mistakes should fail at
startup.

**Decision**
- A `BudgetKey` is built with `BudgetKey.of(...)`, which aligns `windowStart` to the window.
- A `GLOBAL` key always uses the subject `"global"`, so there is only one global counter.
- A `Limit` needs at least one cap, and a set cap must be above zero. A zero cap is treated as a
  mistake, not as "block everything".
- At most one limit per scope and window; token and cost caps go in the same limit.

**Consequence**
Windows roll over by themselves: a new window gives a new key with an empty counter.

## 9. Reserve before a call, settle after (2026-10-22)

**Context**
Two parallel requests can both pass a "check, then call" and overspend together.

**Decision**
- `UsageLedger.reserve(Map<BudgetKey, Limit>, Amount)` checks and holds the estimate on every key in
  one atomic step, or on none. Each limit travels with its key so a Redis Lua script can do the same
  in one round trip.
- Reaching a cap exactly is allowed; going over is denied.
- `commit` replaces the estimate with the actual usage and is never denied, because the call already
  happened. `release` drops it without charging.
- Settling a reservation twice does nothing, because a stream can complete and be cancelled at the
  same time.
- A call is charged to the window it was reserved in, even if it ends after midnight.
- The in-memory ledger uses one lock for all counters and drops counters of ended windows once a day,
  unless a reservation on them is still open.

**Consequence**
- A budget can be exceeded by at most the actual-minus-estimate difference of the calls in flight.
  This goes in the README.
- The in-memory ledger loses spend on restart and only works for a single instance.

## 10. BudgetService and observe mode (2026-10-22)

**Context**
Advisors should only pass in who is calling and what was used; all budget rules live in one
plain-Java class.

**Decision**
- `Decision` has three cases: `Allowed` (from `admit`), `Reserved(reservation)` (from `reserve`) and
  `Denied(reason, retryAfter)`.
- `admit` runs once per request and only checks committed spend. Calls in flight are checked by
  `reserve`, so a parallel overestimate can't cause a false denial.
- `retryAfter` is the time until the window ends in the configured zone. The reason never contains
  the subject id, because it is sent to the client.
- Subjects are given as one id per scope. `GLOBAL` limits always apply; a limit whose scope has no id
  is skipped.
- `OBSERVE` mode never blocks: it logs a WARN and still records the spend
  (`UsageLedger.reserveUnchecked`). The `REJECT` unknown-model policy still applies.
- Missing usage is charged at the estimate (see 2).

**Consequence**
- Teams can start in `OBSERVE`, compare the numbers with their provider dashboard, then switch to
  `ENFORCE`.
- Every ledger implementation needs both reserve methods.

## 11. Finding who a request is for (2026-10-23)

**Context**
`BudgetService` needs one id per scope, but a `ChatClient` call doesn't say who it is for.

**Decision**
- A chain of `SubjectResolver`s, asked in this order: advisor params (`SubjectParams`), then the
  Spring Security principal, then an HTTP header. For each scope the first resolver with an id wins.
- Spring Security and the header only give a user id. Tenant and session ids come only from advisor
  params.
- Blank ids count as missing and fall through to the next resolver. Anonymous Spring Security users
  get no id, so visitors don't share one budget.
- The header can be set by the caller to any value, so it is only for apps behind a gateway that sets
  it. Header ids longer than 128 characters are ignored, so a client can't fill the ledger with huge
  keys.
- If no id is found, only `GLOBAL` limits apply.

**Consequence**
- Advisor params work everywhere, including streams, WebFlux and background jobs. Spring Security
  and the header only work on servlet request threads.
- A spoofed header can't get past the `GLOBAL` limit.

## 12. Admission advisor runs once per request, on the caller's thread (2026-10-23)

**Context**
The budget check must run once per request, outside the tool loop (see 1), and on the thread that
holds the Spring Security context and the HTTP request.

**Decision**
- `AdmissionAdvisor` implements `CallAdvisor` and `StreamAdvisor` directly, not `BaseAdvisor`.
  `BaseAdvisor` runs stream logic on `Schedulers.boundedElastic()`, where the security context and
  the request are gone. The stream chain itself runs on the subscribing thread.
- Its order is `HIGHEST_PRECEDENCE + 100`, before chat memory (`+ 200`) and `ToolCallingAdvisor`
  (`+ 300`), so a denied request never touches memory or tools.
- A denial throws `BudgetExceededException`, which carries `Decision.Denied` (reason and
  `retryAfter`). In a stream it arrives as `Flux.error`. The model is not called.
- An admitted request gets a `RequestBudgetContext` in the advisor context: the subject ids plus
  per-request counters for model calls and cost. `ToolCallingAdvisor` copies the context into each
  loop round, so every model call of the request shares the same object.
- No subject found logs one WARN, then DEBUG, so apps with only global limits aren't flooded.

**Consequence**
- WebFlux apps must pass the user id as an advisor param.
- On a denied stream, Spring AI's `MessageAggregator` logs an `ERROR Aggregation Error` with our
  exception. This is library behaviour and goes in the README.

## 13. Metering each model call (2026-10-23)

**Context**
Inside the tool loop every model call must be reserved, charged and, on failure, released, and a
runaway loop must be cut off.

**Decision**
- `MeteringAdvisor` is the innermost advisor (`LOWEST_PRECEDENCE - 100`), so it runs once per model
  call and its estimate sees the final prompt.
- Per-request caps live in core: `RequestLimit(maxModelCalls, maxCost)` and
  `BudgetService.checkRequest(callsSoFar, costSoFar)`. Reaching a cap denies the next call, with no
  `retryAfter`, because the next request starts with fresh counters. Observe mode only warns.
- The input estimate covers message text, tool-call arguments and tool results. Spring AI's
  `Prompt.getContents()` leaves out tool results, which are often the largest input in a loop. Tool
  schemas and images are not counted.
- The output estimate is the request's max tokens, or 1,024 if none is set.
- Reserve and settle use the model named in the request, not the one in the response, so settle can
  never fail on an unknown model after the call happened. No model name means `"unknown"`, so the
  unknown-model policy decides.
- Provider usage is converted so `input` counts all input tokens. Anthropic's prompt tokens exclude
  cache reads and writes, so they are added; it is recognised by its native usage class name.
  Cache counts larger than the prompt are always added, so the conversion can't fail.
- A failed call releases its reservation and the error is rethrown. `settle` returns what it
  charged, and that is added to the request's cost.

**Consequence**
- A request can go over its cost cap by at most one call.
- Bedrock's cache semantics are not verified yet (see `IDEAS.md`).
- `MeteringAdvisor` fails fast if `AdmissionAdvisor` is not registered.

## 14. Settling a streamed model call (2026-10-27)

**Context**
A stream can complete, fail or be cancelled, and providers report usage only on its last chunk, if
at all. In the tool loop, each round is a separate stream.

**Decision**
- The reservation is made when the stream is subscribed, not when it is built.
- On complete, the usage of the last chunk that reported any is charged, or the estimate if none did.
- An error before the first chunk releases the reservation, like a failed blocking call.
- A cancel, or an error after the first chunk, charges any usage already seen. Otherwise it follows
  `InterruptedStreamPolicy`: `CHARGE_ESTIMATE` (default) or `RELEASE`.
- The reservation is settled exactly once, because complete, error and cancel can race.
- Settling happens in `doOnComplete`/`doOnError`/`doOnCancel`, not `doFinally`. `doFinally` runs
  after the tool loop may already have started its next round, so the per-request cost cap would
  miss this round's cost.
- Anthropic's stream reports usage with a different native type (`MessageDeltaUsage`), so both types
  are recognised when cache tokens are added to the input.

**Consequence**
- A dropped stream may be overcharged by up to its estimate, but by default it is never free.
- Spring AI's advisor chain can be run only once, so subscribing twice to the same `stream()` result
  fails instead of making a second, metered call.

## 15. Returning 429 for a used-up budget (2026-10-27)

**Context**
A used-up budget should reach HTTP clients as "slow down and retry later", not as a server error.

**Decision**
- An optional `@RestControllerAdvice` answers `BudgetExceededException` with 429 and a
  `ProblemDetail` whose detail is the denial reason, which holds no subject id.
- `Retry-After` is in whole seconds, rounded up, so a client that waits that long finds the window
  reset. It is left out when the wait is zero (a per-request cap), because the next request may
  start at once.
- The handler is ordered first, so an app's catch-all `@ExceptionHandler(Exception.class)` can't
  turn the denial into a 500. Apps that want their own response turn the handler off.

**Consequence**
- Once a streamed response has started, its status can't change; the error ends the stream instead.

## 16. Configuration properties (2026-10-28)

**Context**
All settings live under `token-allowance.*`. They should be easy to write in YAML, show up in IDE
autocomplete, and a mistake should stop the app at startup instead of letting a budget be wrong.

**Decision**
- One `@ConfigurationProperties` record, `TokenAllowanceProperties`, with nested records and
  defaults set by `@DefaultValue`: enabled, `enforce` mode, `UTC` zone, header `X-User-Id`,
  unknown models `reject`, interrupted streams `charge-estimate`, the 429 handler on.
- Model prices are a list of entries with a `name`, not a map keyed by model name. Spring Boot
  reads dots in map keys as nested properties, so keys like `gpt-4.1` or `llama3.2` would silently
  break unless written as `"[gpt-4.1]"`.
- Money amounts are bound as `BigDecimal` and turned into `Money` by the auto-configuration, so no
  custom converter is needed and amounts stay exact. Enums bind straight to the core enums
  (`allow-uncosted` becomes `ALLOW_UNCOSTED`).
- Validation is split. Jakarta annotations check single fields (`@NotNull`, `@Positive`). Rules
  that span fields (a limit needs a cap, `default-price` needs a default price) stay in the core
  constructors and are not repeated.
- Unknown models are rejected by default: an app that adds the starter without prices gets a clear
  error instead of cost limits that quietly don't count.
- The subject settings are named `subject`, matching `SubjectResolver`. Store settings are left
  out until the Redis ledger exists.

**Consequence**
- Jakarta validation runs only when the app has a validator (for example
  `spring-boot-starter-validation`). Without one, the core constructors still fail at startup, with
  a less precise message.
- With the default `reject`, every model in use needs a price before the first call succeeds.

## 17. Auto-configuration (2026-10-29)

**Context**
Adding the starter should be enough: the budget service, the advisors and the 429 handler are
built from `token-allowance.*`, and every `ChatClient` the app gets from Spring AI is metered.
Users must still be able to replace any part.

**Decision**
- The advisors are added through a `ChatClientBuilderCustomizer` bean. Spring AI 2.0 deprecated
  `ChatClientCustomizer` for removal, and applies every customizer bean to each `ChatClient.Builder`
  it creates.
- Every bean is `@ConditionalOnMissingBean`, so an app's own bean of the same type wins. The
  customizer is matched by name instead, since apps often have customizers for other reasons.
- The subject resolver chain is one bean. Spring Security and the header resolver are added only
  when their classes are present. Separate resolver beans would clash with the chain, which is a
  resolver itself.
- The 429 handler lives in a nested configuration that needs a web application and can be turned
  off with `token-allowance.web.exception-handler=false`, so non-web apps never load web types.
- Prices that look like per-token prices are caught at startup: more than 6 decimals fails with a
  message saying prices are per million tokens, and a non-zero price below 0.001 logs a warning.

**Consequence**
- A `ChatClient` built by hand with `ChatClient.builder(..)` is not metered unless the app adds the
  two advisor beans itself.
- Turning a part off means defining a replacement bean; there are no per-bean switches.
