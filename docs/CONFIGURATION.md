# Configuration

The server creates `config/mc_ai_assistant.json`. Start and stop once before editing. `/aiadmin reload` reloads configuration and persona files; restart after changing process environment variables. No credential is included in the distribution.

## Model transport and credentials

Supported adapters are `openai_responses` (POST `<baseUrl>/responses`) and `anthropic_messages` (POST `<baseUrl>/messages`). A Chat Completions-only endpoint is **not** interchangeable. Search/Wiki currently require the Responses adapter. Confirm the provider supports the adapter and selected model before use.

The generated config starts with the OpenRouter endpoint and model route examples inherited from development. Model availability, routing and prices can change; review both `primaryModel` and `fallbackModel` before enabling access. A name containing `free` is not a cost guarantee. Automatic fallback uses the same endpoint and credential.

For an endpoint exposing the supported Responses format, edit these fields in the generated config (replace the model IDs):

```json
{
  "protocol": "openai_responses",
  "baseUrl": "https://openrouter.ai/api/v1",
  "primaryModel": "REPLACE_WITH_SUPPORTED_MODEL_ID",
  "fallbackModel": "REPLACE_WITH_SUPPORTED_FALLBACK_ID",
  "secretFile": "model.secret",
  "fullConversationLog": false
}
```

This is a field example, not a ready-to-use model selection. Put only the key text in `config/model.secret`, with no JSON or labels. Restrict access to the server service account. Relative secret paths resolve from the server's `config/` directory. Never commit actual key files. HTTPS is required for remote endpoints; HTTP is accepted only for loopback testing.

Model key priority: `MC_AI_ASSISTANT_SECRET_FILE` environment path, then `secretFile`, then `OPENROUTER_API_KEY` environment value. The environment variable name is historical and also supplies a key to a custom endpoint. Firecrawl uses `MC_AI_ASSISTANT_FIRECRAWL_SECRET_FILE`, then `firecrawlSecretFile`, then `FIRECRAWL_API_KEY`. Credentials are sent only to the configured service; ensure that service is one you trust.

## Limits and history

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Startup switch; requests without a key are rejected |
| `playerCooldownSeconds` | `8` | Per-player cooldown |
| `maxConcurrentRequests` | `2` | Server-wide active requests |
| `inputMaxCharacters` | `300` | Input Unicode code points |
| `outputMaxTokens` | `1200` | Model output ceiling |
| `requestTimeoutSeconds` | `60` | Total request deadline |
| `dailyRequestLimit` | `100` | Server-wide outbound-attempt budget, not per-player rounds |
| `timezone` | `Asia/Shanghai` | Daily budget rollover and audit date |
| `historyTurns` | `6` | In-memory context pairs per conversation |
| `historyIdleMinutes` | `30` | Idle context expiry |
| `fullConversationLog` | `false` | Opt-in complete conversation audit |
| `logRetentionDays` | `30` | Cleanup threshold for enabled audit logs |

Search rewriting, retrieval, model attempts, retries and fallback can consume several budget units for one visible answer. A request budget is not a monetary cap; also set provider-side spending limits. Audit expiry cleanup occurs on subsequent audit writes, not on a periodic background schedule. Turning logging off does not erase old files.

## Optional retrieval

`/ai search` uses Firecrawl (`searchEngine: firecrawl`, `firecrawlBaseUrl: https://api.firecrawl.dev/v2`, `searchMaxResults: 3`) and needs a separate key. Queries may include information from recent context.

`/ai wiki` uses the English Wiki API at `https://minecraft.wiki/api.php` and a local cache. `wikiFirecrawlFallback` defaults to `false`; enabling it allows bounded Firecrawl requests for Wiki article content when the API is blocked and requires the Firecrawl key. It does not guarantee availability. Fallback pages are marked as current-page content instead of inventing a revision ID. Cached pages and retrieved text are not part of the software license.

On a corrupt preferences file the service fails instead of silently changing a player's private default to public. Back up and repair the file deliberately. Server offline-mode identities are not equivalent to authenticated accounts; private delivery relies on the server's player identity system.
