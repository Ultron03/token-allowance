# Ideas

Possible features after v0.1. Not planned yet.

- **Bundled price list.** Ship a price table for common models, generated from LiteLLM's `model_prices_and_context_window.json` (MIT) and converted to our own format, so users don't have to type prices. Read our copy, not their live file, so a change in their format can't break startup. User config still overrides it.
- **Tiered pricing.** Some models charge more above a prompt size (for example long-context prices). v0.1 uses one price per model.
- **Cache-write duration.** Anthropic charges 1.25x input for 5-minute cache writes and 2x for 1-hour writes. v0.1 has one cache-write price per model.
- **Bedrock cache tokens.** Bedrock Converse probably reports input tokens without cache reads and writes, like Anthropic. Verify, then add its usage type to the cache-excluded rule in `UsageMapping`.
- **Configurable output estimate.** Calls without max tokens reserve 1,024 output tokens. Make this a setting if it turns out too high or too low.
- **Reclaim reservations of a crashed instance.** If an app instance stops between reserve and settle, its estimate stays reserved in Redis until the window's key expires. A lease index with a sweep in the reserve script could give it back sooner.
- **Local fallback while Redis is down.** A third failure mode next to fail-open and fail-closed: switch to the in-memory ledger during the outage. Each instance then enforces the limit on its own (N instances let up to N times the limit through), and spend counted locally is not written back to Redis afterwards.
- **Budgets per model.** Separate caps per model, for example an expensive model capped at $5 a day while a cheap one is free. Needs the model in the budget key, which changes the Redis key format (counters reset on upgrade). Cost caps already cover most of this.
- **Billing-cycle months.** Monthly windows follow the calendar in one time zone. A subscription that renews on the 15th would need a per-subject cycle start.
- **Calls outside `ChatClient`.** `EmbeddingModel`, image and audio models, and direct `ChatModel.call()` are not counted. Embeddings are cheap but add up in bulk indexing jobs.
- **Reset over actuator.** A write operation on the `tokenallowance` endpoint for support to top up or reset a subject. Left out because a budget-changing endpoint needs stronger protection than a read-only one.
