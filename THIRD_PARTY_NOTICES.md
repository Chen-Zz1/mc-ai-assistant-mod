# Third-party notices

Original mod source and its original documentation are offered under the root MIT license. This does not relicense Minecraft, external libraries, fetched web content or service APIs.

| Component | Distribution relationship | License / source |
| --- | --- | --- |
| Gradle wrapper JAR and launch scripts | Included for building | Apache-2.0; [Gradle](https://github.com/gradle/gradle), text in `LICENSES/Apache-2.0.txt`; existing copyright notices retained |
| Fabric Loader and Fabric API | External runtime dependencies, not bundled in the mod JAR | Apache-2.0; [Loader](https://github.com/FabricMC/fabric-loader), [API](https://github.com/FabricMC/fabric-api) |
| Fabric Loom | External build plugin | [Official source and license](https://github.com/FabricMC/fabric-loom) |
| Minecraft and game-provided libraries | Supplied by the separately installed server, not redistributed | Their respective terms; [Minecraft usage guidelines](https://www.minecraft.net/en-us/usage-guidelines) |

The released mod JAR contains the project's compiled classes and metadata, not shaded third-party libraries or the Minecraft game. Gradle downloads build dependencies from their repositories.

Wiki/web content is retrieved only at runtime and remains subject to its source's license and terms. Source links are displayed; no Wiki corpus, runtime cache or third-party chat dataset is distributed. The Wiki evaluation fixture contains queries, source identifiers and expected terms, not article bodies. Fictional persona examples were created for this project.

Minecraft is a trademark of Mojang/Microsoft. This is an independent, unofficial project. It is not approved by or associated with Mojang or Microsoft.
