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
