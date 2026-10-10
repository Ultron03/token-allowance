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
