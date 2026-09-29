# Contributing

Use JDK 25 and the pinned Gradle wrapper: `./gradlew --no-daemon check build`. On Windows use `gradlew.bat`. Fabric Loom and the target Minecraft/Fabric versions are pinned in `gradle.properties`; the wrapper distribution has a SHA-256 pin. CI runs the same checks and uploads only built mod JARs.

The core suite uses synthetic fixtures and loopback HTTP mocks. Do not add paid API calls to ordinary tests. `wikiSmoke` and `wikiEvaluation` are optional public-network checks; read their test entrypoints before running them, and do not confuse retrieval metrics with player acceptance or model-answer quality.

Keep credentials, runtime files, actual player messages and real-person persona data outside the repository. Use fictional examples and reserved example domains. Review changes for newly introduced external services or logging. Preserve third-party license notices, and disclose substantial AI assistance in contributions. Report security issues through the process in [SECURITY.md](SECURITY.md).
