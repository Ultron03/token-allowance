# Token Allowance


[![CI](https://github.com/Ultron03/token-allowance/actions/workflows/ci.yml/badge.svg)](https://github.com/Ultron03/token-allowance/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ultron03/token-allowance-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.ultron03/token-allowance-spring-boot-starter)
[![License](https://img.shields.io/github/license/Ultron03/token-allowance)](LICENSE)
![Java](https://img.shields.io/badge/Java-17%2B-orange)

A Spring Boot starter that enforces per-user, per-tenant and per-session token and dollar budgets on Spring AI `ChatClient` calls. It also caps runaway tool loops, so one request can't make a hundred model calls.

![alice gets three answers, then HTTP 429, while bob is still served](https://raw.githubusercontent.com/Ultron03/token-allowance-demo/main/demo/token-allowance.gif)

Try it without an API key: [token-allowance-demo](https://github.com/Ultron03/token-allowance-demo) runs against a local Ollama model.

## Adoption & Distribution

Token Allowance is published on Maven Central
as a modular set of five artifacts.

### Maven Central Adoption

According to Sonatype Publisher Insights
(**3-month reporting period, as of November 7, 2026**):

the `io.github.ultron03` namespace recorded
the following activity over the last three months:

| Metric | Value |
|---|---:|
| Downloads | 432 |
| Unique sources | 49 |
| Companies | 19 |

### Package Analytics

Package-level analytics are available through Scarf.

The project distributes five Maven artifacts:
- token-allowance-core
- token-allowance-spring-ai
- token-allowance-autoconfigure
- token-allowance-spring-boot-starter
- token-allowance-parent

[View Maven Central](https://central.sonatype.com/)

## Why

An LLM bill grows with every call, and one user, one bug or one tool loop that never ends can run it up overnight. Provider dashboards show the damage the next day. Token Allowance checks the budget before each call and charges the real usage after it, so a used-up budget is answered with `429 Too Many Requests` instead of another paid call.

## How it works

Two advisors are added to every `ChatClient.Builder` that Spring AI creates:

- **Admission** runs once per request, before chat memory and tools. It finds who the request is for and denies it if a budget is already used up.
- **Metering** runs once per model call, including every round of a tool loop. Before the call it reserves an estimate; after the call it replaces the estimate with the usage the provider reported. It also stops a request that reaches its per-request call or cost cap.

Reserving before the call means two parallel requests can't both pass a check and overspend together.

## Requirements

- Java 17 or later
- Spring Boot 4.1
- Spring AI 2.0, with any chat model starter (OpenAI, Anthropic, Ollama, ...)

## Quickstart

**1. Add the starter** next to your Spring AI model starter. The example is a Spring MVC app, so it also uses the web starter:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>io.github.ultron03</groupId>
    <artifactId>token-allowance-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**2. Set prices and budgets** in `application.yml`. Prices are in dollars per million tokens, as on provider pricing pages. The prices below are an example; check your provider's current prices.

```yaml
token-allowance:
  pricing:
    models:
      - name: gpt-4o-mini
        input-per-mtok: "0.15"
        output-per-mtok: "0.60"
        cached-input-per-mtok: "0.075"
  limits:
    - { scope: user, window: daily, max-cost: "0.50" }
    - { scope: global, window: monthly, max-cost: "200.00" }
  per-request:
    max-model-calls: 10
```

**3. Use the `ChatClient.Builder` that Spring AI gives you.** Nothing else changes:

```java
@RestController
class ChatController {

    private final ChatClient chatClient;

    ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping("/chat")
    String chat(@RequestBody String message) {
        return chatClient.prompt().user(message).call().content();
    }
}
```

**4. Call it.** Without Spring Security, the user comes from the `X-User-Id` header:

```bash
curl -X POST localhost:8080/chat -H 'X-User-Id: alice' -d 'Hello'
```

When Alice's daily budget is used up, she gets:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 41520
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "Token budget exceeded",
  "status": 429,
  "detail": "user daily budget exceeded",
  "instance": "/chat"
}
```

`Retry-After` is the number of seconds until the budget's window resets.

**Tip:** start with `token-allowance.mode: observe`. Nothing is blocked; a used-up budget only logs a warning. Compare the numbers with your provider's dashboard, then switch to `enforce`.

## Who a request is for

Budgets with scope `user`, `tenant` or `session` need an id. For each scope, the first of these that has one wins:

1. **An advisor param** on the call. This is the only way to pass tenant and session ids, and the only one that works in WebFlux, streams on other threads and background jobs.

   ```java
   chatClient.prompt()
       .user(message)
       .advisors(a -> a.param(SubjectParams.USER_ID, userId)
           .param(SubjectParams.TENANT_ID, tenantId))
       .call()
       .content();
   ```

2. **The Spring Security principal's name**, if Spring Security is on the classpath. Anonymous users get no id.
3. **The `X-User-Id` header** (configurable). A client can send any value here, so only rely on it behind a gateway that sets it. Values longer than 128 characters are ignored.

A request with no id is only checked against `global` budgets.

## Plans

Users on different plans can get different budgets. `limits` apply to everyone; a plan lists only what differs. A plan's limit replaces the common limit with the same scope and window, and any other plan limit is added:

```yaml
token-allowance:
  limits:
    - { scope: user, window: daily, max-cost: "1.00" }
    - { scope: global, window: monthly, max-cost: "200.00" }
  plans:
    pro:
      - { scope: user, window: daily, max-cost: "10.00" }
      - { scope: user, window: minute, max-tokens: 20000 }
```

A `pro` user gets $10 a day, at most 20,000 tokens a minute, and shares the global $200. Plans can't set `global` limits, since there is one global budget for everyone.

The plan comes from, in this order:

1. **The `SubjectParams.PLAN` advisor param**: `.advisors(a -> a.param(SubjectParams.PLAN, "pro"))`.
2. **A Spring Security authority** named `PLAN_<name>`, for example `PLAN_pro`, set when the user logs in.

Never from a header, because a client could claim any plan. Plan names match ignoring case. A caller with no plan, or with a plan that isn't configured, gets the common `limits`; an unknown plan is logged once.

Spend is counted per user, not per plan. When Alice upgrades from free to pro after spending $0.80 today, she has $9.20 left, not a fresh $10. To give her a fresh start, [reset her budget](#giving-budget-back).

A login authority is fixed until the user logs in again. If your app changes plans while users are logged in, or keeps a customer's cap in its own database, replace the limits instead:

```java
@Bean
LimitResolver limitResolver(ContractRepository contracts) {
    return caller -> {
        String tenantId = caller.subjectIds().get(Scope.TENANT);
        if (tenantId == null) {
            return List.of();
        }
        Money monthlyCap = contracts.monthlyCapOf(tenantId);
        return List.of(new Limit(Scope.TENANT, Window.MONTHLY, null, monthlyCap));
    };
}
```

The resolver is asked before every model call and when the call is charged, so cache slow lookups. It must return at most one limit per scope and window.

## Per-endpoint request caps

`per-request.max-model-calls` and `per-request.max-cost` apply to every request. An endpoint whose requests need a different cap, such as an agent that walks a tool loop, sets its own on its client:

```java
ChatClient agentClient = chatClientBuilder
    .defaultAdvisors(a -> a.param(RequestLimitParams.MAX_MODEL_CALLS, 20)
        .param(RequestLimitParams.MAX_REQUEST_COST, "0.50"))
    .build();
```

A cap the endpoint doesn't set comes from `per-request`. The same params work on a single call with `.advisors(..)`.

## Giving budget back

Support can give back spend that a bug burnt, through the `BudgetService` bean:

```java
budgetService.topUp(Scope.USER, "alice", new Amount(0, Money.ofDollars("2.00")));
budgetService.reset(Scope.USER, "alice");
```

`topUp` takes the amount off her committed spend, never below zero; `reset` clears it. Both apply to the current minute, hour, day and month windows, and both work with the in-memory and the Redis store. Calls still running keep their reservations, and a call that finishes while `reset` runs stays charged. Neither has an HTTP endpoint; expose them through your own admin API if you need one.

## Configuration reference

All properties start with `token-allowance.`. Amounts are in dollars; write them as strings (`"0.50"`) so YAML doesn't turn them into floating-point numbers.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Turns everything off when `false`. |
| `mode` | `enforce` | `enforce` denies requests over budget; `observe` only logs them. |
| `zone` | `UTC` | Time zone in which hour, day and month windows start. |
| `subject.header` | `X-User-Id` | HTTP header with the user id. |
| `pricing.unknown-model` | `reject` | A model with no price: `reject` fails the call, `allow-uncosted` counts its tokens but no cost, `default-price` uses `pricing.default-price`. |
| `pricing.default-price.*` | none | Price for unknown models: `input-per-mtok`, `output-per-mtok`, optional `cached-input-per-mtok`, `cache-write-per-mtok`. |
| `pricing.models[n].name` | none | Model name, matched ignoring case and a trailing date (`gpt-4o-2024-08-06` uses the `gpt-4o` price). |
| `pricing.models[n].input-per-mtok` | none | Input price per million tokens. |
| `pricing.models[n].output-per-mtok` | none | Output price per million tokens. |
| `pricing.models[n].cached-input-per-mtok` | input price | Price of input tokens read from the provider's cache. |
| `pricing.models[n].cache-write-per-mtok` | input price | Price of input tokens written to the provider's cache. |
| `limits[n].scope` | none | `user`, `tenant`, `session` or `global`. |
| `limits[n].window` | none | `minute`, `hour`, `daily` or `monthly`. Windows are fixed: a minute window resets at :00 seconds. |
| `limits[n].max-tokens` | none | Most tokens in one window. |
| `limits[n].max-cost` | none | Most dollars in one window. Each limit needs `max-tokens`, `max-cost` or both. |
| `plans.<name>[n].*` | none | Limits for callers on plan `<name>`, with the same fields as `limits` (see [Plans](#plans)). |
| `per-request.max-model-calls` | none | Most model calls one request may make, tool-loop rounds included. |
| `per-request.max-cost` | none | Most dollars one request may cost. |
| `stream.interrupted` | `charge-estimate` | A stream that stops before the provider reports usage: `charge-estimate` charges the estimate, `release` charges nothing. |
| `web.exception-handler` | `true` | Answers a used-up budget with 429 and an unreachable budget store with 503. Turn it off to handle `BudgetExceededException` and `LedgerUnavailableException` yourself. |
| `store.type` | `in-memory` | `in-memory` keeps spend in each instance; `redis` shares it across instances (see below). |
| `store.failure-mode` | `auto` | What happens to new calls while Redis can't be reached: `fail-open`, `fail-closed`, or `auto` (open in `observe` mode, closed in `enforce` mode). |
| `thresholds` | `80,100` | Percents of each limit at which a `BudgetThresholdReached` event is published, each from 1 to 100 (see [Alerts](#alerts)). |

The app fails at startup on an invalid setting, such as a price with more than six decimal places. That is almost always a per-token price where a per-million price was meant.

## Sharing budgets across instances

Spend can also be kept in Redis, so every instance of your app counts against the same budgets. Add the Redis module and Spring Boot's Redis starter:

```xml
<dependency>
    <groupId>io.github.ultron03</groupId>
    <artifactId>token-allowance-redis</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```yaml
spring:
  data:
    redis:
      host: localhost
      timeout: 500ms     # the default is 60 seconds per command
token-allowance:
  store:
    type: redis
```

Each reserve and settle is one Lua script, so parallel calls on different instances can't overspend together. All keys share the hash tag `{ta}`, so on Redis Cluster they live on one node.

**When Redis is down.** With `fail-closed`, new calls get `503 Service Unavailable`. With `fail-open`, they go ahead without budget checks, and their spend is not recorded, not even after Redis is back. Calls already running finish either way; their charge is lost and logged. The outage is logged once at WARN when it starts and at INFO when Redis is back.

Set `spring.data.redis.timeout`. Without it, every call waits up to 60 seconds for Redis before the failure mode applies.

With Spring Boot Actuator, Boot's own `redis` health indicator turns `/actuator/health` DOWN while Redis is unreachable, also in `fail-open` mode, where the app still serves calls. The Kubernetes liveness and readiness groups don't include it by default; leave it out of them if you fail open.

## Metrics

With Spring Boot Actuator, or any other `MeterRegistry` bean, Token Allowance records these meters:

| Meter | Type | Tags | What it counts |
|---|---|---|---|
| `token.allowance.decisions` | counter | `stage` (`admission`, `request`, `reservation`), `outcome` (`allowed`, `denied`, `observed`, `ledger-unavailable`), `scope` (`user`, `tenant`, `session`, `global`, `none`) | Every admission and reservation, and every time a per-request cap is reached. `observed` means over budget but let through in `observe` mode. `scope` is the budget that ran out. |
| `token.allowance.cost` | counter, USD | `model`, `charge` (`usage`, `estimate`) | Cost charged to budgets. `estimate` means the provider reported no usage. |
| `token.allowance.tokens` | counter | `model`, `charge` | Tokens charged to budgets. |
| `token.allowance.estimate.ratio` | distribution summary | `model` | Reported tokens divided by the estimate reserved before the call. Above 1 means the estimate was too low. |

No meter is tagged with a user, tenant or session id. Turn them all off with `management.metrics.enable.token.allowance=false`.

Spring AI already records the tokens each model call used (`gen_ai.client.token.usage`). `token.allowance.tokens` differs in that it counts what was charged, including estimates for calls without reported usage.

To record the same events somewhere else, define a `BudgetListener` bean; it is called after every decision and every charged or released call. For charged and released calls, listening for the `CallSettled` application event is simpler (see [Budget events](#budget-events)).

## Actuator endpoint

With Spring Boot Actuator, the `tokenallowance` endpoint shows where a subject stands in the current windows. Like most endpoints, it is not exposed until you include it:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,tokenallowance
```

`GET /actuator/tokenallowance/{scope}/{subjectId}`, for example `/actuator/tokenallowance/user/alice`. Add `?plan=pro` to see the limits of a plan:

```json
{
  "scope": "user",
  "subjectId": "alice",
  "plan": null,
  "budgets": [
    {
      "window": "daily",
      "windowStart": "2026-11-08T00:00:00Z",
      "resetsAt": "2026-11-09T00:00:00Z",
      "maxTokens": 100000,
      "maxCost": 1.000000,
      "committed": { "tokens": 4200, "cost": 0.012600 },
      "reserved": { "tokens": 1500, "cost": 0.002000 }
    }
  ]
}
```

There is one entry per limit of the scope, for the plan asked for. A replaced `LimitResolver` is asked for a caller that has only this one id, so a resolver that needs the tenant to find a user's limits can't answer here. `committed` is what finished calls used; `reserved` is what calls still running hold. Costs are in US dollars. For the global budget, use any subject id, for example `/actuator/tokenallowance/global/all`. A scope without limits answers `404`.

The endpoint shows any user's spend to whoever can reach it, so secure it like your other actuator endpoints.

## Budget events

Token Allowance publishes two application events. Both hold subject ids, so don't use them as metric tags.

### Alerts

When a call takes a subject's committed spend to 80% or 100% of a limit (see `thresholds`), Token Allowance publishes a `BudgetThresholdReached` application event:

```java
@Component
class BudgetAlerts {

	@Async
	@EventListener
	void onThresholdReached(BudgetThresholdReached event) {
		if (event.percent() == 100) {
			// event.key().subjectId() has used up event.limit() until event.resetsAt()
		}
	}
}
```

Each threshold fires once per subject and window, from the call that crossed it, even with several instances sharing Redis. A call that jumps past several thresholds publishes one event for each, lowest first. Only charged calls count, not calls still in flight. Events are also published in `observe` mode, so `100` shows who would have been denied. While the budget store can't be reached, nothing is recorded and no event is published.

### Audit log

Every model call publishes a `CallSettled` event once it is charged or released:

```java
@Async
@EventListener
void onCallSettled(CallSettled call) {
	auditLog.save(call.model(), call.keys(), call.estimate(), call.reported(), call.charged(), call.outcome());
}
```

`keys` are the budgets the call counted against, with their subject ids. `estimate` is what was reserved before the call, `reported` is the usage the provider reported, and `charged` is what the budgets were charged. `outcome` is `CHARGED` (the reported usage), `ESTIMATE_CHARGED` (the provider reported no usage) or `RELEASED` (the call failed, nothing charged). The event never holds prompt or response text. It is published even while the budget store can't be reached; the charge itself is then lost.

### Listener threads

Event listeners run on the thread of the chat call, after it has been charged. Use `@Async` (with `@EnableAsync`) for anything slow, such as sending an email or writing to a database; `CallSettled` fires on every model call, so this matters most there. An exception from a listener is logged and does not fail the chat call.

## Replacing parts

Every bean steps aside when your app defines one of the same type:

| Bean | Replace it to |
|---|---|
| `TokenEstimator` | Count tokens exactly with your model's tokenizer. |
| `PriceCatalog` | Load prices from somewhere other than properties. |
| `LimitResolver` | Read limits from your own store, such as a customer's contract (see [Plans](#plans)). |
| `UsageLedger` | Store spend somewhere other than memory or Redis. Throw `LedgerUnavailableException` when your store can't be reached, so the failure mode applies. `commit` returns each budget's committed spend after the charge, read in the same atomic step, so budget alerts fire exactly once. `refund` backs `topUp` and `reset`. |
| `SubjectResolver` | Find the user, tenant, session or plan your own way. |
| `Clock` | Control time in tests. |

The builder customizer steps aside only for a bean named `tokenAllowanceChatClientBuilderCustomizer`, so your own `ChatClientBuilderCustomizer` beans work next to it.

A `ChatClient` built by hand with `ChatClient.builder(chatModel)` gets no advisors. Add them yourself:

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(admissionAdvisor, meteringAdvisor)
    .build();
```

## Limitations

- **Costs are estimates, not invoices.** They use the prices you configure and the usage the provider reports. Check them against your provider's bill.
- **A budget can be exceeded a little.** Each call reserves an estimate first and is charged its real usage afterwards; the call has already happened, so that charge is never refused. With several calls in flight, a budget can go over by the difference between their estimates and their real usage. A request can go over its own `per-request.max-cost` by at most one call.
- **Windows are fixed, not sliding.** A user can spend a full minute cap at 10:00:59 and another one at 10:01:00, so for a moment twice the cap goes through.
- **In-memory spend is per instance.** With the default `store.type`, spend is lost on restart and each instance of your app has its own budgets. Use Redis to share them.
- **A crashed instance leaves its estimates reserved.** If an instance stops between reserving and settling a call, that estimate stays counted in Redis until the budget's window ends.
- **The pre-call token estimate is approximate.** The default estimator assumes one token per three characters, rounded up. For English and most code this is higher than the real count, which is the safe direction. For Chinese, Japanese and Korean text it can be about three times too low. Tool schemas and images are not counted. The output estimate is the request's max tokens, or 1,024 if none is set. After the call, the provider's token count replaces the estimate; the estimate is charged only when the provider reports no usage.
- **A denied stream logs an ERROR line.** When a budget denies a streamed call, Spring AI's `MessageAggregator` logs `Aggregation Error` with the `BudgetExceededException`, even when your app handles the exception. The log line comes from Spring AI and can be ignored.
- **A stream can be subscribed only once.** Spring AI runs its advisor chain once per `stream()` call. Subscribing twice to the same result fails instead of making a second call; call `stream()` again for a new request.
- **A streamed response that has started can't become a 429.** If a budget runs out during a tool loop after the first chunk was sent, the HTTP status is already fixed, so the error ends the stream instead.


## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and send a pull request.

## License

[Apache License 2.0](LICENSE)
