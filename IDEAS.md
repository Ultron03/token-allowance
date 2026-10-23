# Ideas

Possible features after v0.1. Not planned yet.

- **Bundled price list.** Ship a price table for common models, generated from LiteLLM's `model_prices_and_context_window.json` (MIT) and converted to our own format, so users don't have to type prices. Read our copy, not their live file, so a change in their format can't break startup. User config still overrides it.
- **Tiered pricing.** Some models charge more above a prompt size (for example long-context prices). v0.1 uses one price per model.
- **Cache-write duration.** Anthropic charges 1.25x input for 5-minute cache writes and 2x for 1-hour writes. v0.1 has one cache-write price per model.
- **Bedrock cache tokens.** Bedrock Converse probably reports input tokens without cache reads and writes, like Anthropic. Verify, then add its usage type to the cache-excluded rule in `UsageMapping`.
- **Configurable output estimate.** Calls without max tokens reserve 1,024 output tokens. Make this a setting if it turns out too high or too low.
