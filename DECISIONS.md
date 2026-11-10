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

## 18. The starter brings a validator (2026-10-31)

**Context**
Building a demo app on 0.1.0 showed that an app with only webmvc, a model starter and Token
Allowance fails at startup with `NoProviderFoundException`. The properties are `@Validated`, the
autoconfigure module brings the Jakarta Validation API, and Boot validates whenever the API is
present, so it needs an implementation that nothing provided.

**Decision**
- The starter depends on `spring-boot-starter-validation`, so adding the starter is still enough.
- Validation stays on. Making the API optional instead would let a wrong setting through silently.

**Consequence**
- Hibernate Validator is on the classpath of every app that uses the starter.
- Fixed in 0.1.1; 0.1.0 needs `spring-boot-starter-validation` added by hand.

## 19. Redis key schema (2026-10-31)

**Context**
The Redis ledger must keep the same counters as the in-memory one, shared by every instance of an
app. One reserve changes several budgets at once (user, tenant, global), so a Lua script has to
reach all of their keys, also on Redis Cluster.

**Decision**
- Every key starts with the hash tag `{ta}`, so all keys share one Cluster slot and one script can
  touch them.
- A budget is a hash at `{ta}:budget:{scope}:{window}:{windowStartEpochSeconds}:{subject}` with the
  fields `committed_tokens`, `committed_micros`, `reserved_tokens` and `reserved_micros`. A missing
  field is 0. The subject goes last because only it may contain `:` or `{`.
- A budget key expires one day after its window ends, so a call that spans the window end can still
  settle. Java computes the time, since Redis doesn't know the configured zone, and every script
  that writes a budget key sets the expiry again.
- An open reservation is a key `{ta}:reservation:{id}` that lives one day. Settling deletes it and
  changes the counters only if the delete removed something, so settling twice is a no-op. The
  amount and keys come from the `Reservation` the caller passes in.
- Time comes from the app's `Clock`, not from Redis.

**Consequence**
- On Cluster, all budget keys live on one node.
- If an instance stops between reserve and settle, its estimate stays reserved until the budget key
  expires. Reclaiming it is in `IDEAS.md`.
- A call that settles more than a day after it reserved is not charged.

## 20. Redis failure mode and store settings (2026-11-07)

**Context**
With the Redis ledger, a Redis outage would otherwise turn every chat request into a 500. Some apps
would rather keep serving without budgets, others would rather stop spending money they can't
count.

**Decision**
- `RedisUsageLedger` turns a refused connection (`DataAccessResourceFailureException`) or a command
  timeout (`QueryTimeoutException`) into core's `LedgerUnavailableException`. Other Redis errors,
  such as a failing script, are bugs and pass through.
- `BudgetService` applies `LedgerFailureMode`. `FAIL_OPEN` admits the request and returns a
  reservation that was never stored, so settling it changes nothing. `FAIL_CLOSED` rethrows.
  `store.failure-mode: auto`, the default, fails open in observe mode and closed in enforce mode.
- Settle and release never throw for an outage, in either mode: the call already happened, and
  throwing would lose a response that was paid for. The charge is lost and logged.
- The outage is logged once at WARN when it starts and once at INFO when the ledger answers again.
- Fail-closed answers 503 with a fixed ProblemDetail, not 429: the client didn't use up a budget,
  and `Retry-After` until the window resets would be wrong.
- No health contributor of our own. Spring Boot's `redis` health indicator already pings the same
  connection.
- `store.type` picks the ledger with a property condition on each ledger bean, so the result doesn't
  depend on the order beans are registered in. The Redis bean lives in its own nested configuration
  that only loads when the Redis module is on the classpath.

**Consequence**
- Spend during a fail-open outage is never recorded, also not after Redis is back.
- Without `spring.data.redis.timeout`, Lettuce waits 60 seconds per command before the failure mode
  applies. The README tells users to set it.
- `store.type: redis` without the Redis module fails at startup because no `UsageLedger` bean
  exists.

## 21. Metrics through a listener port (2026-11-08)

**Context**
Observe mode is only useful if users can see what would have been denied and how far the estimate
is off. Core must stay free of Micrometer, and the planned audit event needs the same facts as the
meters.

**Decision**
- Core gets a plain-Java `BudgetListener` with two events: `DecisionMade` (stage, outcome, scope of
  the budget that ran out) and `CallSettled` (model, keys, estimate, reported usage, charge,
  outcome). `BudgetService` calls every listener; Micrometer is one adapter, in the spring-ai
  module.
- Decisions carry a `stage` tag. Without it, an allowed admission and an allowed reservation would
  land in one count. Per-request caps report only when reached, so a model call is not counted
  twice.
- Observe mode reports `observed`, not `allowed`, so the meters show what enforce mode would deny.
- `release` takes the model, so a released call is reported like a charged one.
- Listeners run on the caller's thread after the ledger work, and their exceptions are not caught.
  A failing listener fails the request but can't leave a reservation open.
- Tags are limited to model, stage, outcome, scope and charge. Subject ids stay in `CallSettled`
  for audit listeners and never reach a tag.
- The estimate ratio is recorded only when the provider reported usage; for an estimate charge it
  would always be 1.

**Consequence**
- Any `BudgetListener` bean is called, so users can keep their own audit log without new API.
- An interrupted stream charged its estimate counts as `usage` with ratio 1, pulling the ratio
  slightly toward 1.
- `token.allowance.tokens` overlaps Spring AI's `gen_ai.client.token.usage`; it differs by counting
  charged estimates.

## 22. Actuator endpoint for current spend (2026-11-08)

**Context**
Operators need to answer "why was alice denied, and when can she call again?" without reading
Redis by hand.

**Decision**
- `GET /actuator/tokenallowance/{scope}/{subjectId}` returns one entry per limit of the scope:
  window, window start, reset time, caps, and committed and reserved spend.
- Which keys apply and when a window resets stays in core (`BudgetService.statusOf`); the endpoint
  only formats the result.
- Costs are dollar numbers with six decimals (`Money.toDollars()`), not micro-dollars, so they read
  like the configured prices.
- For the global scope the subject id is ignored, because there is one global budget.
- A scope without limits answers 404. A ledger outage is not handled by the failure mode: the read
  fails with 500, and Boot's `redis` health indicator already reports the outage.
- No list of all subjects: in Redis it would need a key scan.
- The endpoint is only registered when the app exposes it, like Boot's own endpoints.

**Consequence**
- Anyone who can reach the endpoint can read any subject's spend; apps secure it like other actuator
  endpoints.

## 23. Budget threshold and settled-call events (2026-11-08)

**Context**
Meters show totals and the actuator endpoint answers only when asked. Apps also want to act the
moment a user nears a budget: warn them, or tell an operator before requests start failing.

**Decision**
- `BudgetService` checks, after each charge, whether the call moved a budget's committed spend from
  below a threshold to at or above it, and tells `BudgetListener.onThresholdReached`. Reserved
  estimates don't count, as in admission.
- Thresholds are percents from 1 to 100, `80,100` by default, applied to every limit. A threshold
  is reached when either cap is at it, compared in whole numbers (`used * 100 >= cap * percent`).
  A call passing several thresholds reports each, lowest first.
- `UsageLedger.commit` returns each key's committed spend after the charge, read atomically with
  it (under the lock in memory, from `HINCRBY` in the Redis script). Commits on one key are
  serialised, so exactly one call crosses each threshold, across instances, with no "already sent"
  state; a new window is a new key and starts over. Reading `current()` after `commit` was
  rejected: two concurrent commits on the global budget could both report the crossing, or
  neither.
- The core record `BudgetThresholdReached` is published as the application event as it is, with
  no Spring copy of it. `ApplicationEventBudgetListener` catches exceptions from event listeners
  and logs them, because the call has already been charged.
- Events also fire in observe mode. During a ledger outage the charge is lost, so no event fires.
- `CallSettled` (from 5.1) is published the same way, once per charged or released model call,
  so apps can keep their own audit log. It is published even during a ledger outage.

**Consequence**
- Custom `UsageLedger` implementations must return the committed totals from `commit` (breaking
  change in 0.3.0).
- Event listeners run on the chat thread; slow work needs `@Async`.
- The event holds the subject id, so it is for alerts and audit logs, never metric tags.

## 24. Minute and hour windows (2026-11-09)

**Context**
Daily and monthly budgets don't stop a script from spending a whole day's budget in one minute.

**Decision**
- `Window` gets `MINUTE` and `HOUR`. Hours follow the configured zone's local hour, so a zone with a
  half-hour offset starts hours at :30 UTC.
- Windows stay fixed, not sliding. A sliding window would need per-call timestamps in the ledger
  and in the Redis scripts; fixed windows reuse the existing keys.
- Redis keeps minute and hour keys one hour after their window ends instead of one day, and the
  in-memory ledger evicts ended windows every minute instead of every day, so short-window counters
  don't pile up.

**Consequence**
- Across a window boundary a subject can use up to twice the cap in a moment. Documented under
  Limitations.

## 25. Plans and replaceable limits (2026-11-09)

**Context**
Every caller got the same limits. Real apps have plans (free, pro) and customers on contracted caps
that live in the app's database.

**Decision**
- Core port `LimitResolver` turns a `Caller` (subject ids plus an optional plan) into that caller's
  limits. `BudgetService` asks it on admission, before each model call and when a call is charged
  (thresholds are measured against the caller's own limits). Its result is checked for two limits
  of one scope and window on every call.
- The default `PlanLimits` reads `token-allowance.limits` and `token-allowance.plans.<name>`. A plan
  only lists what differs: its limit replaces the common one with the same scope and window, the
  rest are added. Plans can't set `GLOBAL` limits, because one shared budget can't have a cap per
  caller. Names match ignoring case; an unknown plan gets the common limits and a WARN once.
- The plan comes from the `SubjectParams.PLAN` advisor param, then a `PLAN_<name>` Spring Security
  authority, never from a header (a client could claim any plan). It is read by `SubjectResolver`
  through a new default method `resolvePlan`, so existing lambda resolvers keep compiling.
- Spend stays keyed by subject, not plan. An upgrade only moves the cap; a fresh start is the
  app's call, through `reset`.
- `BudgetService.admit`, `reserve` and `settle` take a `Caller` instead of a subject map, and
  `statusOf` takes the plan. The public ten-argument constructor takes a `LimitResolver`; the
  released six- and eight-argument ones still take a list.

**Consequence**
- Breaking for code that calls `BudgetService` directly (0.3.0).
- A custom resolver is called several times per request, so slow lookups need a cache.
- The actuator endpoint resolves limits for a caller with only the one id it was asked about.

## 26. Per-endpoint request caps (2026-11-09)

**Context**
`per-request.max-model-calls` applied to the whole app. A chat endpoint needs one or two model calls
per request, an agent endpoint may need twenty.

**Decision**
- An endpoint sets `RequestLimitParams.MAX_MODEL_CALLS` and `MAX_REQUEST_COST` as advisor params,
  usually once with `defaultAdvisors(..)` on its client. Each cap it sets replaces the configured
  one; a cap it doesn't set comes from `per-request` (`RequestLimit.orElse`).
- The caps don't depend on the plan. They guard against runaway loops, which depend on what the
  endpoint does; plan budgets already limit what a caller can spend.
- The admission advisor reads them once per request into `RequestBudgetContext`. A param that is
  not a number greater than zero fails the request with `IllegalArgumentException`.

**Consequence**
- An endpoint can raise or lower a cap but not remove one; it sets a high number instead.

## 27. Top-up and reset (2026-11-09)

**Context**
When a bug burns a user's budget, support had no way to give it back short of restarting the app
or deleting Redis keys.

**Decision**
- `BudgetService.topUp(scope, subjectId, amount)` takes an amount off the subject's committed spend;
  `reset(scope, subjectId)` takes off all of it. Both act on the current window of every window
  length, so they don't depend on which limits or plan apply.
- New ledger port method `refund(key, amount)`: committed spend goes down, never below zero, and
  reservations stay. In Redis a small script does it, and a missing key stays missing so no key
  without an expiry is created.
- `reset` reads the committed spend and refunds it; a call that settles in between stays charged.
- No actuator write operation: an endpoint that changes budgets needs more protection than one that
  reads them. Apps expose it through their own admin API.

**Consequence**
- Custom `UsageLedger` implementations must add `refund` (breaking in 0.3.0).

## 28. Unknown models without a cost cap, and conversation ids (2026-11-09)

**Context**
With the default `reject` policy, a model without a price failed every call, even when no limit
capped cost. An app that only wanted token limits on a local or self-hosted model had to invent a
price. Apps that use chat memory also had to pass their conversation id a second time to get
session budgets.

**Decision**
- `reject` only rejects an unpriced model when a cost cap applies to the call: a `max-cost` on one
  of the caller's limits, or a per-request cost cap (configured or set by the endpoint). Otherwise
  the tokens are counted with no cost and a WARN is logged once per model. A cost cap is never
  skipped silently.
- `BudgetService.reserve` takes the endpoint's request caps, so it can tell. `settle` never rejects:
  reserve already accepted the model.
- `AdvisorParamSubjectResolver` uses Spring AI's `ChatMemory.CONVERSATION_ID` param as the session
  id when no session id param is set.

**Consequence**
- Token-only setups need no pricing configuration.
- Costs of unpriced models show as zero in metrics and events.

## 29. Configurable output estimate (2026-11-10)

**Context**
A call reserves its whole output estimate before it runs: the request's max tokens, or 1,024. The
demo recording showed the effect: a user with $0.000465 of a $0.0006 budget left was refused,
because one reserved answer cost $0.0005, while real answers cost $0.00004.

**Decision**
- `TokenEstimator` gets `estimateOutputTokens(maxTokens)`, a default method with the old rule, so
  custom estimators keep compiling. The constant moves from `MeteringAdvisor.DEFAULT_OUTPUT_TOKENS`
  to `TokenEstimator.DEFAULT_OUTPUT_TOKENS`.
- `ConservativeTokenEstimator(expectedOutputTokens)` reserves a typical answer instead, never more
  than the request's max tokens. Property `token-allowance.estimate.output-tokens`; unset keeps the
  old behaviour.
- The estimate is a reservation only: the reported usage is still charged in full afterwards.

**Consequence**
- With the setting, a budget can go over by the difference between real and expected answer
  length for each call in flight. Documented under Limitations.

## 30. The user id header is opt-in (2026-11-11)

**Context**
The `X-User-Id` header was read by default. Without a gateway that sets it, a client could send a
new id on every request and only global limits applied, or send someone else's id and use up
their budget. With Spring Security, a logged-out request to an open endpoint fell through to it.

**Decision**
- `token-allowance.subject.header` has no default; the header resolver is only in the chain when
  it is set. A set value must be a header name without spaces.

**Consequence**
- Apps that relied on the header without setting it must add `subject.header: X-User-Id` in
  0.3.1. Called out in the release notes.

## 31. Tokens only in the total are output (2026-11-11)

**Context**
Spring AI's Gemini model maps `completionTokens` to `candidatesTokenCount`. Thinking tokens
(`thoughtsTokenCount`, billed at the output price) and tool-use prompt tokens are only in
`totalTokens`, so they were never charged.

**Decision**
- When `totalTokens` is larger than input plus output, the difference is charged as output. No
  provider type is checked, so the OpenAI-compatible Gemini endpoint is covered too.

**Consequence**
- Tool-use prompt tokens are priced as output, a little high. Providers whose total is smaller
  than our input (Anthropic, where we add the cache tokens) are unaffected.

## 32. Timed-out calls follow the interrupted-stream policy (2026-11-11)

**Context**
A call that threw was always released. A read timeout doesn't mean the provider did no work: it
may have finished and billed the answer after the client stopped waiting.

**Decision**
- A failure whose cause chain holds an `InterruptedIOException` or a class named
  `*TimeoutException` is settled like an interrupted stream: the estimate by default, nothing with
  `stream.interrupted: release`. A `ConnectException` or `HttpConnectTimeoutException` anywhere in
  the chain means the request never left, so it is released. The same applies to a stream that
  times out before its first chunk.
- Matching by class name covers clients we don't depend on, such as Netty's `ReadTimeoutException`.

**Consequence**
- A connect timeout reported as a plain `SocketTimeoutException` is charged: the safe side.
- Retries inside the model client stay invisible; documented under Limitations.

## 33. Reservations time out after an hour (2026-11-11)

**Context**
A reservation that was never settled (crashed instance, Redis down at settle time, a Redis
timeout after the script ran) stayed in the reserved counters until the window ended: up to a
month on a monthly budget, so users were refused with budget left. In memory, it stayed until
restart and kept its window's counter from being evicted.

**Decision**
- `UsageLedger.RESERVATION_TIMEOUT` is one hour, longer than any model call should take.
- Redis: each budget key has a sorted set `{ta}:reservations:...` of `id:tokens:micros` members
  scored by timeout, with the budget key's expiry. `reserve.lua` first drops timed-out members and
  takes their amounts off; `settle.lua` takes the estimate off only the keys that still hold the
  member and charges every key, so a late settle never takes an estimate off twice. The
  `{ta}:reservation:<id>` key is gone. `RedisUsageLedger` takes a `Clock`; the old constructor
  uses the system clock.
- In memory: open reservations carry their timeout and are dropped by the minute eviction.

**Consequence**
- A call that settles after its reservation was dropped everywhere is not charged.
- Reservations made by 0.3.0 and still open when 0.3.1 starts keep their estimate until their
  window ends, as before. Clocks of instances sharing a Redis must roughly agree.

## 34. Failing listeners and the input estimate (2026-11-11)

**Context**
A `BudgetListener` that threw during `reserve` failed the call after the ledger had reserved, so
the reservation was never settled; during `settle` the user got an error for a call that was
charged. The input estimate left out tool definitions, sent with every call, and images.

**Decision**
- `BudgetService` catches and logs a `RuntimeException` from each listener and goes on with the
  next one. `ApplicationEventBudgetListener` no longer catches on its own.
- `PromptText` adds each tool's name, description and input schema. `TokenEstimator` gets
  `estimateMediaTokens(count)`, a default method reserving `DEFAULT_MEDIA_TOKENS` (1,600, about the
  largest Anthropic image) per media item.

**Consequence**
- Long audio or video is still underestimated before the call; the reported usage corrects it.
