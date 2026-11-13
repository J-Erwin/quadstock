# QuadStock

QuadStock is a configurable Minecraft stock-market simulation for **Paper 26.3 and Java 25**. Players browse recorded market history, trade through their server economy, manage holdings and subscribe to private portfolio reports. Qualified shop activity gently influences associated industries.

## Build and install

```sh
./gradlew clean build
```

Use a Java 25 JDK. Install `build/libs/quadstock-1.0.0.jar` in Paper's `plugins` directory. Paper resolves the declared SQLite JDBC library on first startup, requiring access to Maven Central. SQLite stores application state in `plugins/QuadStock/quadstock.db`; configuration remains in `config.yml`.

Trading requires Vault and a compatible economy provider. XConomy is not required. QuickShop-Hikari is optional. Without these integrations, market browsing and simulation remain available. Choose the settlement mode explicitly in console before trading; see the [administrator guide](docs/admin-guide.md).

This database starts fresh. Prior local test files are not imported. Stop Paper and move the old QuadStock data directory aside before installing this build for a clean local playtest. Keep only one QuadStock JAR installed. The application will not automatically delete arbitrary files in a server's plugin directory.

## Play

- `/qm display`: administrator menu for protected physical market displays on signs or above existing blocks. See [setup and controls](docs/admin-guide.md#physical-market-displays).
- `/qm`: industry directory with 16 configured companies and their linked items.
- `/qm ticker MINE`: recorded price charts with Live, hour/day/week/month columns and page navigation.
- `/qm trade MINE`: request and explicitly confirm a purchase or sale; use Custom amount or `/qm trade MINE sell 37` for other quantities.
- `/qm portfolio`: combined holdings, cost basis and realized/unrealized profit.
- Hold Tab: private live portfolio totals and industry holdings, with gain/loss colors. Optionally supply your own artwork using the [Tab resource-pack setup](docs/admin-guide.md#tab-portfolio-and-icon-resource-pack).
- `/qm performance MINE`: per-ticker value or cash-flow-adjusted profit history.
- `/qm transactions`: latest personal operations and settlement status.
- `/qm reports MINE`: industry events and commerce evidence.
- `/qm subscribe` and `/qm unsubscribe`: private chat reports.

`/quadstock` is the full command; `/qm` is its alias. Ticker-aware commands use `default-ticker`, or the first configured industry when omitted. `/qs` remains available to QuickShop. Market time is independent of Minecraft daylight and only advances with server ticks.

## Documentation

- [Administrator configuration](docs/admin-guide.md)
- [Trading setup and recovery](docs/trading.md)
- [Developer architecture and build](docs/development/README.md)
- [Command reference](docs/development/commands.md)
- [Simulation and long-term growth review](docs/development/simulation-review.md)

`./gradlew javadoc` generates searchable HTML at `build/docs/javadoc/index.html`. `./gradlew formatJava` applies the pinned Java formatter; `clean build` applies/checks formatting, builds documentation and runs tests. The build does not start a Minecraft server.

Local test tools are under `scripts/`, outside the product source sets. The optional `scripts/quadstock.sh` helper supports build, prepare and explicit start operations. The standard deliverable is the main JAR; no local test helper is packaged with it.

Industry news is configured in `stories.yml` (221 bundled narratives). Assign collections and tune ordinary/rare special events in `config.yml`; `/qm config check` validates both. Special events accelerate price movement over market hours and appear in combined portfolio news digests. See the admin guide for event controls and the explicit local reset required by the new market snapshot version.

## License and source distribution

Original QuadStock code and documentation are licensed under the [MIT License](LICENSE), copyright 2026 J-Erwin. Third-party components retain their own terms; see [third-party notices](THIRD_PARTY_NOTICES.md), including the Paper GPL and optional QuickShop GPL/AGPL integration considerations. No Minecraft artwork is included.

The build includes license notices in the plugin JAR and produces `build/distributions/quadstock-1.0.0-source.zip` with the matching QuadStock source and build files. Distribute that source archive alongside the JAR. It does not include the separately installed server or dependencies; distributing a combined server bundle may require additional corresponding source and notices.

## Automated builds and releases

GitHub Actions builds pushes and pull requests for `dev` and `main` using Java 25. Each successful build uploads the JAR, matching source ZIP, notices and SHA-256 checksums. Development builds use versions such as `1.0.0-dev.42.1+abcdef123456`; pull requests use a `pr` suffix. To reproduce one from its source ZIP, pass its version with `bash gradlew clean build -PbuildVersion=<version>`.

Only a pushed stable tag such as `v1.0.0` publishes a GitHub Release. Its version must match `build.gradle.kts`, its commit must be reachable from `main`, and all checks must pass. Push/merge the release commit to `main` before pushing its annotated tag. A branch push alone never publishes a release, and a tag on a dev-only commit is rejected. Existing releases are not overwritten by reruns.

Use `main` as the GitHub default/release branch and `dev` for development. Before the first CI run, publish the prepared `main` and `dev` branches; local workflow files do not change GitHub branch settings automatically.
