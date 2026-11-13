# Third-party notices

## Scope

Original QuadStock source, tests, configuration, narratives and documentation are licensed under the root MIT LICENSE, copyright 2026 J-Erwin. This grant does not relicense third-party software, trademarks, or administrator-supplied assets. License texts retain their original owners and terms.

The plugin JAR contains QuadStock classes and resources, not shaded dependency implementations. The Gradle wrapper is third-party code shipped in the source repository. No Minecraft artwork or game/server implementation is bundled. Minecraft item identifiers and references to client fonts are not copies of texture files.

## Components

| Component | Version / relationship | Upstream terms | Included text |
| --- | --- | --- | --- |
| Gradle wrapper | 9.8.2; gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar in source | Apache-2.0; original author notices retained in scripts and JAR | licenses/Apache-2.0.txt |
| Paper / Bukkit API | 26.3.build.211-stable; compile/test API, supplied by server at runtime | GPLv3 with MIT grants for specific contributors; not a blanket MIT API | licenses/GPL-3.0.txt, licenses/Paper-LICENSE.md |
| VaultAPI | 1.7; compile-only API, optional runtime integration | LGPLv3; copyright respective VaultAPI contributors | licenses/LGPL-3.0.txt and licenses/GPL-3.0.txt |
| SQLite JDBC | 3.53.4.0; loaded separately by Paper and used in tests | Apache-2.0 and retained upstream Zentus notice | licenses/SQLite-JDBC-LICENSE.txt, licenses/SQLite-JDBC-LICENSE.zentus |
| Adventure | 5.2.0 API resolved through Paper; provided by server | MIT; respective Kyori contributors | licenses/Adventure-MIT.txt |
| QuickShop-Hikari | Optional, separately installed, no pinned version or bundled classes | Upstream states GPLv3/AGPLv3, with additions after June 29, 2024 following AGPLv3; check installed revision | licenses/QuickShop-Hikari-LICENSE.txt (includes AGPLv3), licenses/GPL-3.0.txt |
| JUnit | 4.13.2; tests only | EPL-1.0 | licenses/EPL-1.0.txt |
| google-java-format | 1.35.0; build formatting only | Apache-2.0 | licenses/Apache-2.0.txt |

These notices cover the repository's shipped third-party wrapper and directly declared tools/dependencies plus the runtime integrations above. They are not a complete license inventory of a separately installed Paper server, QuickShop installation, or every transitive build dependency. Upstream distributions retain their own notices. Reassess before bundling additional libraries.

## Paper, QuickShop and distribution

MIT is GPL-compatible. Original QuadStock code may remain MIT-licensed while a combined work is subject to applicable GPL/AGPL terms. MIT is not an exemption from those terms. QuadStock subclasses Bukkit JavaPlugin and calls Paper APIs. Its optional QuickShop adapter invokes QuickShop event/model methods in-process through reflection. Neither compileOnly nor reflection establishes a copyleft exception.

The reviewed Paper licensing statement identifies GPLv3 with contributor-specific MIT grants. The reviewed QuickShop root license and API module provide no general permissive linking exception. QuickShop is not pinned by QuadStock: its license statement, exact installed revision and any separate permissions must be considered when distributing or operating a combination. This review does not conclusively determine derivative-work status for every deployment.

Distribute the matching QuadStock source archive alongside each binary, with these notices and license texts intact. Preserve the source, build instructions and notices required for any other covered components you distribute; links alone are not a substitute where the applicable license requires providing corresponding source. A server bundle needs its own compliance assessment. For an AGPL-covered modified/combined network application, provide the corresponding source offer to remote users where section 13 applies. The QuadStock source ZIP contains QuadStock's buildable sources, not the source of the entire server stack, and is not by itself a certification of GPL/AGPL compliance for that stack.

VaultAPI and its use are covered by LGPLv3. QuadStock uses a separately installed provider and does not prevent replacing or modifying it or debugging a modified library. If distributing a combined work, retain the applicable LGPL/GPL notices and meet its source/relinking requirements. Do not replace library licenses with QuadStock's MIT license.

## Sources and review basis

Reviewed 2026-11-13. Exact cached POM/JAR notices were inspected for VaultAPI 1.7, SQLite JDBC 3.53.4.0, JUnit 4.13.2, Adventure 5.2.0 and google-java-format 1.35.0 (including its parent POM). The Gradle wrapper's embedded Apache text is reproduced unchanged. Paper and QuickShop notices were obtained from the upstream branches below; they are not proof of every historical release's terms.

- Paper: https://github.com/PaperMC/Paper/blob/main/LICENSE.md and https://github.com/PaperMC/Paper/blob/main/paper-api/LICENCE.txt
- VaultAPI 1.7: https://github.com/MilkBowl/VaultAPI/blob/1.7/license.txt
- SQLite JDBC: https://github.com/xerial/sqlite-jdbc
- Gradle: https://github.com/gradle/gradle/blob/v9.8.2/LICENSE
- Adventure: https://github.com/PaperMC/adventure/blob/main/5/license.txt
- QuickShop: https://github.com/QuickShop-Community/QuickShop-Hikari/blob/hikari/LICENSE and https://github.com/QuickShop-Community/QuickShop-Hikari/blob/hikari/quickshop-api/build.gradle.kts
- QuickShop licensing documentation: https://quickshop-community.github.io/QuickShop-Hikari-Documents/docs/about/licenses
- JUnit: https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt
- google-java-format: https://github.com/google/google-java-format
- GNU plugin guidance: https://www.gnu.org/licenses/gpl-faq.html#GPLAndPlugins
- GNU compatibility guidance: https://www.gnu.org/licenses/license-list.html#Expat
