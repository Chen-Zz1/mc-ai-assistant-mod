# Known limitations

- Beta support is limited to the documented Minecraft/Fabric/Java versions. A fresh two-player acceptance session for this public candidate remains pending.
- In-game interface text is Chinese. English documentation does not imply a translated UI.
- The adapters support Responses and Anthropic Messages, not a generic Chat Completions endpoint. Retrieval currently requires Responses.
- Model IDs, provider routing, API availability and fees can change independently. Limits count outbound attempts; they are not per-player daily rounds or a guaranteed spend cap.
- Wiki retrieval is experimental. Network blocking, absent pages, edition/version ambiguity and retrieval quality can prevent a grounded answer. Firecrawl fallback is opt-in and may cost extra.
- No commands, item grants, movement, NPC entity control or world editing are executed by the AI.
- Context is bounded and temporary, not persistent personal memory. Persona examples are prompts, not trained weights. Output similarity and accuracy are subjective and model-dependent.
- Private replies rely on the server's identity/permission model and do not conceal data from operators or external providers.
- No telemetry backend, managed API service, web-based persona arena or real-person card pack is included. No Modrinth/CurseForge approval is claimed.
