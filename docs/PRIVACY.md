# Data and privacy

## External data flow

When a player submits a request, the configured model provider receives the question, relevant recent context, system instructions, selected persona material and retrieved excerpts as applicable. The Responses adapter also sends the player's UUID in the `user` field. A gateway may route data onward to another provider; their retention, training policies and fees are controlled by them, not this mod.

Web search sends a rewritten query (or a fallback question) to Firecrawl. Wiki retrieval sends topic/keyword queries to the Wiki API; when enabled, Wiki fallback also contacts Firecrawl. Search rewriting itself uses the configured model with recent context. API keys are sent in authorization headers to their configured endpoint. Ordinary network metadata is visible to contacted services.

Before the first in-game request for each runtime/policy, the mod displays a private informational notice of service hostnames and audit status. It is a notice, not an opt-in consent gate. It repeats after relevant notice settings change and may repeat after the bounded notice cache evicts an entry. `/ai help` always shows the current information. Server operators should disclose their policy before inviting players.

## In-game visibility and local storage

Initial chat is public. `/ai private ...` and `/ai settings private` limit delivery of the question and answer to the requesting player. They do not hide requests from the service provider or server operator. Public/private contexts are separate. Server logs, command logging, proxies and other mods may record data independently.

This mod's full conversation audit is **off by default**. If an operator enables `fullConversationLog`, records include player name/UUID, question, answer, time, visibility, selected model/persona, status and retrieval diagnostics. They are stored under `config/mc_ai_assistant/logs/`. Expiry is checked during later audit writes using file modification time and `logRetentionDays`; cleanup is not guaranteed while logging is off or the server is stopped.

Regular server logs can still contain UUIDs, timing, error categories and bounded Wiki diagnostics. Player defaults are persisted by UUID in `config/mc_ai_assistant/player-preferences.json`. The daily outbound counter is stored in `config/mc_ai_assistant-quota.json`. Retrieved public article text/metadata are cached in `config/mc_ai_assistant/wiki-cache.json`. Personas are administrator-controlled JSON files under `config/mc_ai_assistant/personas/`.

Conversation context lives in memory, expires after idle time and is cleared on restart. `/ai clear` clears the caller's temporary context; `/aiadmin clear` clears all temporary contexts. Neither deletes audit files, preferences, cached articles, backups or provider-held records. `/ai settings reset` resets preferences to ordinary/public, so use it deliberately.

## Persona materials

Only fictional cards are distributed. Adding a card sends its examples to the chosen model during persona requests. Do not add private chats, third-party identifiers or real-person material without the necessary permission to use and disclose it. A `consentConfirmed` flag is an operator assertion, not proof of consent. AI labels are retained, but a prompt cannot guarantee that a model never repeats example text.

## Operator responsibilities

Choose trustworthy endpoints; protect the server filesystem, key files and backups; describe which providers and logging settings you use; and handle deletion requests across your own storage and provider accounts. Turning off logging affects future mod audit writes only. The project has no bundled analytics, hosted backend or shared API credential. This beta is not a privacy boundary against a server administrator or a compromised host.
