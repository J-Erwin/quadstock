# QuadStock developer guide

The Java source is the product implementation. Package names describe responsibilities; the plugin has no supported HTTP API or third-party Java SDK. Use **Javadoc**, the JDK's standard documentation tool, for its Java contracts. Swagger/OpenAPI would apply only if a real HTTP API is added. See the [Javadoc tool documentation](https://docs.oracle.com/en/java/javase/25/docs/specs/man/javadoc.html) and [Gradle Java plugin](https://docs.gradle.org/current/userguide/java_plugin.html).

## Build and read

Use Java 25. `./gradlew clean build` compiles the JAR, runs JUnit tests, applies/checks Java formatting and validates Javadoc. `./gradlew formatJava` applies Google Java Format 1.35.0. Open `build/docs/javadoc/index.html` for class/package navigation and search. Private implementation members are included; their visibility is not an external compatibility promise. Doclint validates HTML/references; repetitive tags on trivial accessors are not required.

## Components

| Package | Responsibility | Lifetime/thread |
| --- | --- | --- |
| root | Thin Paper entry point | One plugin instance |
| `runtime` | Composition, scheduling, checkpoints, shutdown | Server thread |
| `command` | Player/console routing and permissions | Server thread |
| `config` | Strict YAML and durable identity validation | Startup or read-only check |
| `data` | Calendar, deterministic simulation, chart intervals | Server thread; synchronized state access |
| `trading` | Quotes, payment protocol, holdings and valuation | Serialized server-thread protocol |
| `persistence` | SQLite connections, checkpoints, normalized journal | Short-lived connections; exclusive application writer |
| `commerce` | QuickShop adapter, diagnostic eligibility and bounded demand | Callback queue; server-thread drain |
| `notifications` | Durable opt-in and session-only delivery cursors | Server thread |
| `display` | SQLite registrations, sign/floating rendering, admin drafts, protection | Server thread |
| `ui` | Inventory ownership/navigation plus focused screen renderers | Server thread |

`MarketRuntime` creates one ledger, calendar and gateway, then ticker-scoped models, desks and menu controllers. `MarketCommands` authorizes command actions. `MarketMenus` owns inventory identity, navigation and refresh; `TradingScreens`, `ChartScreens`, `PortfolioScreens` and `ReportScreen` render their respective views.

## Money and settlement

A quote is a fixed offer, not a funds reservation. Confirmation checks expiry, day, journal generation, wallet/holdings and funding. It persists PREPARED, then IN_FLIGHT, before one Vault call. Explicit rejection becomes FAILED. Explicit success becomes PAID, then COMMITTED. Only COMMITTED affects holdings. Unknown outcomes remain IN_FLIGHT and block settlement until console reconciliation.

SQLite cannot make an external provider call part of its transaction. Never retry an ambiguous payment automatically. Failed journal writes poison the writer and require restart/reconciliation. Database commits precede publishing new memory state. `LedgerStore` is the persistence boundary; `SqliteLedgerStore` inserts an intent and first step atomically, then appends transitions with an expected-previous-state check.

Monetary totals are `BigDecimal`; SQLite stores exact decimal text. Display/simulation marks use doubles. Partial sales allocate average cost with eight decimal places and HALF_EVEN rounding; a full exit consumes the remainder. No fees or spread currently apply.

## Database

`quadstock.db` uses schema version 1, WAL, foreign keys, a five-second busy timeout and FULL synchronization. Unknown schema versions or corrupt state fail explicitly. The process lock `quadstock.lock` excludes a second application writer.

- `trades`: immutable payment terms, ticker/market identity and insertion sequence.
- `trade_steps`: ordered states, market ticks and reconciliation evidence, referencing the trade ID.
- `state`: named checkpoints for calendar, markets, subscriptions, policy and ticker assignments.

Market checkpoints retain a bounded binary payload inside SQLite with checksum and version validation. They include random state, actual observations, events and bounded commerce evidence. These internal payloads are not user-editable APIs. The runtime saves the calendar and all market checkpoints in one transaction. Payment history is normalized and no longer rewrites a binary journal on every transition. Startup validates/replays the journal once; committed transitions update only the affected position in memory.

There is no 100,000-operation cutoff. History is still loaded into memory at startup, and reserve/history queries scan it. SQLite writes currently execute on the server thread. Removing full-journal rewrites is an improvement, not a load-testing result: large-server profiling, indexed query projections and an asynchronous single-writer design remain release work. Do not move Vault calls to arbitrary worker threads.

## Time, charts and reports

The calendar begins at tick zero, Month 1 Day 1. One hour is 1,000 ticks, a day 24 hours, a week seven days and a month 28 days. Sleep and `/time` do not alter it. Each historical chart column spans one selected period; live charts subdivide a rolling hour. Missing observations remain gaps. UI refresh is measured using monotonic wall time and never changes a quote's terms.

Preferences persist; online report schedules do not. Reconnecting starts a new interval without backlog. Event alerts share a player cooldown and may coalesce events with the same timestamp.

## Contributing

Physical market displays are implemented; manual in-game acceptance remains outstanding.

Document side effects, units, ownership, ordering and failure behavior where they matter. Keep rendering free of payments, treat configuration as a validated startup snapshot, and keep command authorization outside the pure economic model. Add focused regression coverage for economic, storage and lifecycle changes. Do not introduce provider-specific dependencies or local test commands into the product JAR.

The current test fixtures and scripts are development tools, not evidence of production load or every provider's correctness. Manual client, economy-provider, commerce-integration and production-load checks remain outstanding.

Event economics live in `events/EventSettings`, narrative loading in `events/StoryCatalog`, and persisted selection/cooldown state in `events/EventEngine`. `LivingMarket` consumes resolved events and stores their immutable terms with the SQLite snapshot. `notifications/PortfolioNews` owns bounded pending news separately from delivery cadence; the report sender acknowledges batches after dispatch. `stories.yml` is installed only when absent and is validated by the same settings loader used by startup and `config check`.
