# Minecraft AI Assistant

[中文说明](README.zh-CN.md) · [Downloads](https://github.com/Chen-Zz1/mc-ai-assistant-mod/releases) · [Configuration](docs/CONFIGURATION.md) · [Privacy](docs/PRIVACY.md)

[![Build and self-check](https://github.com/Chen-Zz1/mc-ai-assistant-mod/actions/workflows/build.yml/badge.svg)](https://github.com/Chen-Zz1/mc-ai-assistant-mod/actions/workflows/build.yml)

A server-side Fabric mod for AI chat, fictional character conversations and optional web/Minecraft Wiki retrieval. Players can join with vanilla clients. Server operators supply and pay for their own API services; no shared key or hosted service is included.

**Beta target:** Minecraft Java 26.2, Java 25, Fabric Loader 0.19.3, Fabric API 0.158.0+26.2. Other versions/loaders are not verified. In-game messages are currently Chinese. AI responses can be wrong. This mod generates text and does not execute commands or modify the world.

## Features

- Ordinary and multi-turn chat, public/private replies, persistent per-player defaults.
- Local V1 style cards and V2 dialogue-example cards, with two fictional examples.
- Optional Firecrawl web search and experimental English Minecraft Wiki retrieval with source links.
- Cooldowns, concurrency control, a server-wide daily outbound-request budget and operator controls.
- Full conversation audit logs off by default; a player-facing notice explains external data flow.

## Install

1. Back up your server. Install the matching Fabric server and Fabric API from their official sources.
2. Put `mc-ai-assistant-0.2.0-beta.1.jar` in the server's `mods/` directory. Do not install the sources JAR.
3. Start and stop the server once to generate `config/mc_ai_assistant.json`. Set a supported protocol, endpoint and model IDs using [Configuration](docs/CONFIGURATION.md).
4. Store your API key in a separate file readable only by the server account, set `secretFile`, then restart. No key is bundled. Do not put a key in chat, an issue or a Git commit.
5. Review [Privacy](docs/PRIVACY.md), then try `/ai private hello`. Use `/ai help` and `/ai settings` in game.

Initial ordinary replies are **public**; use `/ai settings private` to change your default. `private` limits in-game delivery, not access by the provider or server operator. The mod's conversation logs default to off; server logs and other mods may still record commands.

## Commands

| Command | Purpose |
| --- | --- |
| `/ai <text>` | Chat using personal defaults |
| `/ai private <text>` / `/ai public <text>` | Override visibility for this request |
| `/ai plain <text>` | Ordinary chat without a selected persona |
| `/ai clear` | Clear your in-memory conversation context |
| `/ai settings` | Show preferences; subcommands: `role <id>`, `ordinary`, `private`, `public`, `reset` |
| `/ai persona list` / `/ai persona <id> <text>` | List/use a configured persona |
| `/ai search <question>` / `/ai wiki <question>` | Optional retrieval; `private`/`public` can prefix these |
| `/aiadmin status`, `reload`, `enable`, `disable`, `clear` | Operator controls; moderator command permission required |

`enable` and `disable` are runtime switches; edit `enabled` for startup behavior. `/aiadmin clear` clears transient conversations, not stored preferences, provider records or audit files.

## Build and validation

With JDK 25: `./gradlew --no-daemon check build` (Windows: `gradlew.bat --no-daemon check build`). The core self-check suite uses local mock HTTP servers and synthetic data; no paid API calls are needed. Dependency downloads still require network access. See [Contributing](CONTRIBUTING.md) and [release validation](docs/RELEASE_CHECKLIST.md).

The initial public source import passed local self-checks and [GitHub CI on Linux](https://github.com/Chen-Zz1/mc-ai-assistant-mod/actions/runs/36518971066), but a fresh two-player in-game acceptance run remains pending. Treat this as a prerelease for testing; review [known limitations](docs/KNOWN_LIMITATIONS.md). No real-person chat exports, private persona packs, operational credentials, production logs or evaluation website are distributed.

## License and disclosure

Original mod code is under [MIT](LICENSE); see [third-party notices](THIRD_PARTY_NOTICES.md). Development made substantial use of AI-generated code and documentation; see [AI disclosure](AI_DISCLOSURE.md). The project is not approved by, associated with or sponsored by Mojang or Microsoft. Minecraft is a trademark of Mojang/Microsoft.
