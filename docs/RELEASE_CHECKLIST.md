# Release validation

Prepared candidate: `0.2.0-beta.1` for Minecraft 26.2. This file records the remaining acceptance procedure; it does not assert that a public release exists.

## Automated checks

Run `./gradlew --no-daemon check build` using Java 25. `selfCheck` executes the actual dependency-free test suite; Gradle's ordinary JUnit `test` task can report NO-SOURCE. Coverage includes local mock transport, retries/fallback, budgets, timeouts/cancellation, conversation separation, persona parsing/reload, source validation, Wiki caching/fallback and privacy defaults. These checks do not make paid requests.

Review the generated main JAR, license metadata, and source archive. Exclude Git history, runtime config, logs, captures, caches and all credentials. Secret scans are one check, not proof that arbitrary data is safe. Use a clean initial repository with deliberate author identity when publishing an extracted source snapshot.

## Manual acceptance still required

- Start a fresh matching Fabric server with the packaged JAR and Fabric API; confirm startup and generated config.
- With two vanilla clients, verify private questions/answers and notices appear only to the requester, while public messages reach both clients; inspect long-answer chunking and source links.
- Confirm `/ai settings` persists across restart and `/ai clear` clears the appropriate context without changing visibility.
- Verify an ordinary chat and a fictional persona request using an explicitly chosen API/model; confirm billing and quota behavior for that provider.
- Exercise optional retrieval if advertising it on the release page, including unavailable-Wiki feedback.
- Capture genuine in-game screenshots for a mod platform listing; do not fabricate them. Record date, build hash, versions and outcomes.

Before uploading, recheck the chosen platform's current content and AI-disclosure policies. GitHub hosting does not imply mod-platform acceptance. Publish the mod JAR and source, not Minecraft/Fabric server bundles or a world save.
