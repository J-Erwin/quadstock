# QuadStock administrator guide

Start with the packaged industries. You only need an economy provider, an explicit settlement choice, and a few preference settings. Advanced controls are optional. This guide describes the current product build; it does not promise production-scale storage or compatibility with every economy/shop plugin.

## 1. Essential setup

1. Install the standard QuadStock JAR on the supported Paper/Java version. Start once to generate `plugins/QuadStock/config.yml`.
2. For trading, install Vault and a compatible economy provider. XConomy is **not required**. Browsing and simulations work without Vault. QuickShop-Hikari is optional for commerce signals.
3. Choose or change settlement in the server console:

   ```text
   quadstock settlement select RESERVE_BACKED 10000
   ```

   Or, for liquidity without reserve constraints:

   ```text
   quadstock settlement select UNLIMITED_LIQUIDITY 0
   ```

4. Open `/qm`. The packaged Overworld Exchange has 16 configured industries. Each has independent prices, history and simulated events. New accounts own no shares.

**Reserve-backed:** purchases fund the ticker's market reserve; sales spend that market reserve. Tickers in one market share funds, but have separate daily payout limits. Markets do not share reserves. Shop transactions never fund these reserves. There is no automatic replenishment or administrator top-up command yet.

**Unlimited liquidity:** sales can create currency without reserve limits. All markets use the installation's selected settlement mode, provider and currency precision. Per-market mode overrides are not currently supported.

Modes can be changed with the same console command without resetting data; see [transition accounting](trading.md#changing-modes-without-a-reset). Existing reserves and same-day payouts from both modes are retained. Unlimited-mode trades never contribute to reserve accounting.

Daily payout limits apply in both settlement modes. Each industry’s explicit limit overrides the settlement default. In unlimited mode, an inherited default of `0` is uncapped; positive overrides still apply. **Existing explicit industry limits (typically `5000` in the bundled configuration) now also constrain unlimited-mode sales.** Allowance is shared across players per ticker and resets each 24,000-tick market day. Partial offers require confirmation of the offered quantity; zero-capacity sales make no wallet call. Administrator configuration, holdings and journal data are preserved; no database migration is needed.

The console selection's numeric allowance is the default for industries configured with `daily-payout-limit: inherit`. The packaged AGRI uses `inherit` to preserve the selected installation policy; the other packaged industries use 5,000 each. This number is a spending cap, **not funding**. No initial shares or market reserves are granted by adding industries.

## 2. Changing settings safely

Use `config.yml` for administrator settings. Do not edit the SQLite database or its internal checkpoint payloads.

1. Back up configuration and data, especially before economic tuning.
2. Edit `config.yml` using spaces, not tabs. Copy a complete industry block when adding one. Every documented key is required; unknown/misspelled keys are rejected.
3. Run `/qm config check` (or `quadstock config check` in console). This validates the file **without applying it**. Errors identify the setting or relationship to fix. `/qm config` describes the currently active settings.
4. Restart Paper to apply. Existing quotes expire on restart. Avoid Bukkit `/reload` or plugin hot-reload tools.
5. Check `/qm status`, the menus, and the startup log.

Configuration inspection requires `quadstock.admin` (operators by default). Settlement selection/recovery remains **console-only**, even for operators. Ordinary players do not need admin permission to browse or trade. Commerce diagnostics require `quadstock.commerce.inspect` and include participant identifiers.

An invalid edited file does not change an already running configuration. If restarted with invalid configuration, QuadStock refuses startup instead of using partial settings or resetting data. Fix the file, then restart. Validation covers settings, item names, saved IDs, journal assignments and provider precision; full storage/checksum validation occurs at startup.

## 3. Everyday controls

All changes below apply after restart. “Market” time advances with server ticks; it does not follow daylight, `/time`, sleep, or real-world time while offline.

| Setting | Default | Accepted values | Effect |
| --- | --- | --- | --- |
| `version` | `1` | `1` | Configuration schema; leave unchanged. |
| `ui.refresh-seconds` | `2` | Number, 0.5–30 | Real-world seconds between open-menu refreshes. Faster refresh does not create more price observations. Closed menus are not rendered. |
| `trading.enabled` | `true` | Boolean | Pause/resume **all** new buy/sell confirmations. Browsing, history and simulations remain available. Does not reconcile pending payments. |
| `trading.quote-market-minutes` | `60` | Number, 1–1440 | Quote lifetime in market minutes, rounded to server ticks. Quotes can end earlier at a day boundary or after another journal operation; terms never change silently. |
| `trading.max-shares-per-order` | `10000` | Integer, 1–10000 | Maximum requested whole-share quantity. GUI preset choices above the limit are unavailable. The GUI offers presets plus custom whole-share amounts. |
| `reports.enabled` | `true` | Boolean | Pause/resume automatic messages without deleting player subscription preferences. |
| `reports.interval-market-hours` | `24` | Number, 1–168 | Time between combined portfolio briefings for online subscribers. Login/restart/re-subscription begins a fresh interval; no backlog is replayed. |
| `reports.news-headline-limit` | `4` | Integer, 1–18 | Maximum stories in one combined digest. Remaining stories wait for later delivery slots. |
| `reports.alert-cooldown-market-hours` | `2` | Number, 1–168 | Minimum spacing between relevant industry-event alerts for a subscriber. Events from all held industries share one digest slot. Pending items survive cooldown; the bounded session queue reports overflow explicitly. |
| `commerce.enabled` | `true` | Boolean | Accept eligible shop activity as industry evidence. When false, applied influence is zero; diagnostics can still observe transactions. Simulations and trading continue. |
| `commerce.influence-scale` | `1.0` | Number, 0–1 | Multiplier for `max-outlook-percent`. Effective cap = percentage × scale. At the default 2%, `0.5` permits up to +1%; `1` permits up to +2%; `0` applies none. It does not guarantee a price increase or bypass diversity requirements. |
| `commerce.max-outlook-percent` | `2.0` | Number, 0–100 | Maximum positive reference-outlook adjustment before the influence-scale multiplier. `5` means 5%, not 0.05%. |
| `commerce.minimum-buyers` | `3` | Integer, 1–4096 | Distinct buyers required per ticker over the trailing market day. |
| `commerce.minimum-sellers` | `2` | Integer, 1–4096 | Distinct sellers required per ticker over the trailing market day. |
| `commerce.minimum-participants` | `4` | Integer, 2–8192 | Distinct people across both roles; a person is counted once. |
| `commerce.minimum-pairs` | `3` | Integer, 1–4096 | Distinct unordered buyer/seller pairs required; reversing roles does not create a new pair. |

One market hour is 1,000 ticks, one day is 24 hours, one week is seven days, and one month is 28 days. At normal 20 TPS, a market hour lasts about 50 real seconds and a day about 20 real minutes. Server lag changes elapsed real time. UI refresh uses real time independently.

## 4. Markets and industries

A **market** is a named grouping with its own reserve. An **industry/ticker** has a price, holdings, history, item associations and daily spending limit. A market named OVERWORLD is a logical exchange, not a Minecraft-world filter: currently, item routing applies server-wide.

The `markets` section maps permanent IDs to display names:

```yaml
markets:
  OVERWORLD:
    name: Overworld Exchange
```

Market/ticker IDs must start with an uppercase letter and use 1–16 uppercase letters, digits or underscores. Configure 1–18 markets and 1–18 industries. Display names are 1–64 characters. Each industry must reference a configured market.

| Industry key | Packaged default | Effect / bounds |
| --- | --- | --- |
| `market` | `OVERWORLD` | Permanent market assignment. Determines reserve sharing. |
| `name` | Configured company name | Display label; safe to rename without changing identity. |
| `icon` | Configured item icon | Exact Paper material name representing a real item; AIR and non-item blocks are rejected. |
| `items` | See table below | Nonempty list of exact item material names. No duplicates, including across industries/markets. No custom-NBT, display-name or wildcard matching. |
| `reference-price` | Varies by ticker | Number, 1–5,000. Starting price **only for a new ticker**, and ongoing baseline toward which its simulation responds. Changing it affects future pricing gradually; it does not rewrite saved prices, costs or receipts. |
| `volatility` | Varies by ticker | Number, 0–5. Multiplier for random price noise. Zero removes random noise but does not remove event/commerce/reference movement. |
| `events-enabled` | `true` | Enable future simulated industry events and their price effect. False stops their effect; historical event records remain. |
| `tradable` | `true` | Pause this ticker's buys and sales while retaining its data and visibility. Global trading pause still takes precedence. |
| `daily-payout-limit` | AGRI: `inherit`; others: `5000` | `inherit`, or positive currency amounts up to 1,000,000,000 with at most four decimal places and no more precision than the selected provider supports. Applies in both settlement modes. |

The packaged company names, prices and complete item mappings are maintained in [config.yml](../src/main/resources/config.yml). They cover AGRI, MINE, TIMB, FISH, LIVE, FOOD, FLOR, GEMS, STON, META, WOOD, TEXT, MOBS, REDT, BUIL and EXPL. Items must belong to exactly one ticker. Company names and tuning are configurable; do not assume every company starts at the same price.

Daily usage is shared by all players per ticker, counts committed sales in both modes, and survives restart. It resets each 24,000-tick market day. Purchases do not restore spent allowance. Lowering a limit below today's already-used amount prevents further payouts until the next day; raising it increases today's remaining capacity. Neither change adds reserve money. A smaller funded sale offer requires explicit acceptance; an unfundable sale does not consume holdings.

### Add an industry to an existing market

For example, add this **under the existing `industries:` section** (do not create a second `industries` key):

```yaml
  NETH:
    market: OVERWORLD
    name: Nether Trading
    icon: NETHERRACK
    items: [NETHERRACK, NETHER_QUARTZ_ORE]
    reference-price: 125
    volatility: 0.5
    events-enabled: true
    tradable: true
    daily-payout-limit: 2500
```

Validate, run `/qm reload` as an administrator, and open `/qm`. New industries begin recording at creation time, with no fabricated prehistory. `/qm ticker NETH`, `/qm trade NETH`, `/qm performance NETH` and `/qm reports NETH` address them directly.

### Add a separate market

Add `BUILDERS: { name: Builders Exchange }` under the existing `markets` section. Set the **new** NETH industry's `market` to `BUILDERS` before its first successful startup. Its reserve then begins at zero, separate from OVERWORLD. The same settlement mode/provider applies. World-specific item routing is not implemented.

### Permanent identity rules

Once saved, a ticker ID cannot be removed, renamed or moved to another market by editing configuration. Use `name` for a cosmetic rename and `tradable: false` to pause it. No particular ticker or exchange is required on a fresh installation. The saved identity checkpoint and journal enforce existing assignments; do not delete either to bypass it. A deliberate transfer/retirement tool is future work.

Item mappings and display settings may change on reload or restart. Existing commerce evidence is retained in its original industry and fades over its trailing market day; remapping does not retroactively transfer old evidence. Each new shop transaction routes to exactly one industry. Choose non-overlapping item lists.

## 5. Understanding commerce influence

Only completed, eligible QuickShop-Hikari **SELLING** transactions contribute demand. BUYING is diagnostic only. Unlimited/admin shops, virtual owners, self-trades, custom currencies and invalid amounts are excluded. Normal shops must meet all four configured diversity thresholds after one learning day. Defaults are three buyers, two sellers, four participants and three pairs. These settings apply globally, but each ticker collects and evaluates its own evidence.

Evidence is capped at 48 units per pair and 96 per participant per trailing market day, fades with age, and influences outlook gradually with a six-market-hour half-life. These safeguards remain fixed. The scale setting adjusts influence without allowing a single repeated transaction to dominate. A solo virtual merchant test is expected to be excluded; it does not prove community demand. Use `/qm commerce` for session diagnostics and the selected industry's Reports screen for its applied outlook.

## Runtime reload

Use `/qm reload` or `/quadstock reload` with `quadstock.admin` (operators by default), or from the server console. Extra arguments return usage. The command reloads `config.yml`, `stories.yml`, `tab.yml`, market controllers and integrations from the current JAR. It preserves administrator files and durable holdings, receipts, subscriptions and market history. Replacing a JAR still requires a server restart.

Configuration and saved identities are validated before stopping the active runtime. Invalid files or a journal requiring recovery reject reload without replacing that runtime. A valid reload drains queued commerce, checkpoints state, closes menus/dialogs and the Tab view, cancels scheduled work, unregisters listeners, closes SQLite and starts a fresh runtime. Outstanding quotes and online report schedules are discarded; users must request new quotes. Checkpoint, storage-close or replacement startup failure disables QuadStock; inspect the console, correct the problem and restart the server. Reload never retries an uncertain Vault payment.

## 6. Player navigation

`/qm` lists all configured industries with their market names. `/qm portfolio` combines current holdings and totals across industries/markets. Click a holding for its details; closed-position realized profit remains included in the total. Transaction history includes ticker identity. Performance charts are **per ticker**, available from its position screen or `/qm performance TICKER`; a combined historical portfolio graph is not yet implemented.

Reports on an industry-specific screen, including performance charts, opens that industry’s reports directly. On global screens it opens a directory of all configured industries; select one to read its reports and use **Back · Industries** to return. Trading desk appears only in industry-specific screens, not Markets, the report directory, combined Portfolio or transaction history.

`/qm trade TICKER` opens that industry's desk. `/qm trade TICKER buy|sell AMOUNT|MAX` selects a direction and quantity. MAX replaces the 1,000-share preset: buys use the current wallet balance and rounded unit price; sales use committed holdings. Both respect `trading.max-shares-per-order`. MAX is recalculated on click, then selects a fixed quantity; request and confirm a quote before any payment. Reserve and daily payout limits can still reduce a sale offer. Zero capacity or an unavailable economy disables MAX.

`/qm ticker TICKER`, `/qm preview TICKER`, `/qm performance TICKER`, and `/qm reports TICKER` require an explicit industry. Missing or extra arguments return usage rather than selecting the default industry. Chat subscriptions deliver one combined periodic portfolio report, with alerts relevant to actual holdings.

Historical chart columns represent one full selected hour/day/week/month. Back/Next browse older/newer pages, Latest follows the calendar, and Live shows anchored buckets covering about an hour; see section 10. Price history defaults to twelve market months (336 market days); `history.retention-market-months` accepts whole values from 1 through 12. Gaps remain empty. Views update automatically; there is no manual Refresh button. Icon controls distinguish calendar information (clock) and Latest (nether star).

## 7. Storage, recovery and controls intentionally kept fixed

All persistent application state lives in `plugins/QuadStock/quadstock.db`: normalized payment intents and transitions, plus named checkpoints for markets, calendar, policy, subscriptions and ticker assignments. Money is stored as exact decimal text. WAL mode and FULL synchronization are enabled. The sibling `quadstock.lock` prevents a second application writer.

Back up the database and economy provider together while Paper is stopped. If copying while running, use SQLite's backup API; do not copy only the database file while ignoring an active WAL. A fresh database has no shares, reserve, subscriptions or selected policy. Market time begins at Month 1 Day 1. This build does not import prior local test files.

`quadstock settlement` lists all ticker policies and market reserves, and any pending journal operation. Recovery commands remain in [trading and recovery](trading.md). A pending or uncertain payment blocks all settlement. No automatic retry, reconciliation, currency creation in reserve mode, or mode switch is exposed as a casual configuration toggle.

The following remain fixed by design in this release: calendar durations, five-market-minute sampling, 1–1,000,000 absolute price bounds, pair/participant unit caps and learning/smoothing durations, zero fees/spread, whole shares only, nine chart columns. Changing these requires storage/economic design work rather than another unchecked option. Administrator reserve top-ups, per-market provider/mode selection, retirement/transfers, and production-scale storage benchmarking are not implemented.

## 8. Common mistakes

| Symptom | What to check |
| --- | --- |
| Config check fails | Read the exact key in its message; check spelling, indentation, material names, duplicate item mappings and saved ticker assignments. |
| Changes do not appear | `config check` never applies settings. Run `/qm reload` and inspect `/qm config` for active values. |
| A ticker cannot trade | Check global `trading.enabled`, its `tradable`, selected settlement policy, Vault provider/account, and pending recovery. |
| Sale rejected despite a large wallet | Reserve mode uses the market reserve and ticker allowance, not the seller's wallet. |
| A new market cannot pay profit yet | It starts with no reserve; its buys fund it. Another market's reserve is separate. |
| Charts are mostly empty | The industry is new or the requested dates exceed retained observations. No synthetic history is generated. |
| Shop sale has no visible price impact | Check eligibility, mapped material, learning/diversity/caps, and commerce settings. Influence is small and gradual. |
| Changed reference/volatility did not instantly move price | These govern subsequent simulation steps. Existing stored prices are never reset by configuration. |
| Duplicate or inconsistent economies after rollback | Stop trading and reconcile journal/provider state. Never restore only one side of settlement. |

For an uncomplicated first setup, keep the packaged mappings and tuning, choose settlement explicitly, and adjust only refresh/report cadence. Add one new industry at a time and validate before restarting.

## 9. Permissions and LuckPerms

QuadStock registers standard Bukkit permissions. LuckPerms manages them automatically; QuadStock does not require or write directly into LuckPerms storage.

| Permission | Default | Effect |
| --- | --- | --- |
| `quadstock.use` | Everyone | Browse markets, portfolio and charts; required for player commands/menu actions |
| `quadstock.trade` | Everyone | Select orders, request quotes and confirm buys/sales |
| `quadstock.reports` | Everyone | Open industry reports and subscribe/receive private updates |
| `quadstock.player` | Not granted separately | Bundle of the three player nodes for group assignment |
| `quadstock.admin` | Operators | Configuration inspection, player bundle and commerce inspection |
| `quadstock.commerce.inspect` | Operators | Commerce diagnostics with participant identifiers |

Ordinary players work out of the box. For explicit group assignments in LuckPerms:

```text
lp group default permission set quadstock.player true
lp group moderators permission set quadstock.admin true
lp group spectators permission set quadstock.trade false
```

Replace group names with groups that actually exist on your server. A negative leaf permission overrides its bundle grant under normal LuckPerms resolution. Permissions are checked again on quote/confirmation, so revoking trading access while a player has an open confirmation prevents payment. Console settlement selection/recovery remains console-only, regardless of permission grants. Disabling report access pauses delivery without deleting subscription preferences; players may still unsubscribe.

## 10. Custom orders and Live charts

The trading desk has one **Mode: BUY / Mode: SELL** toggle. BUY uses a green emerald; SELL uses a gold ingot. Clicking switches mode while preserving quantity. The title and quote button also show the active side. Switching mode does not place an order.

The trading screen retains 1/10/100/1000 presets and adds **Custom amount**. It opens an in-game dialog: enter whole shares, choose **Review order**, then **Confirm** the exact quote. Invalid quantities show an error in the amount dialog. **Back — edit amount** preserves the ticker, Buy/Sell side and requested quantity; **Cancel** returns to trade selection. Entering a quantity never moves money. The configured maximum order size applies to custom amounts too. A reserve- or daily-limit-constrained quote explicitly shows the smaller offered quantity before confirmation. Quotes still expire normally; a fresh review is required after expiry.

The optional `/qm trade MINE sell 37` shortcut remains available. It opens trade selection with the supplied amount; it does not execute a trade. Escape dismisses a dialog without placing an order. Dialog buttons expire after ten minutes; reopen Custom amount if an abandoned dialog no longer responds.

Live charts now use nine **fixed 6-minute-40-second market buckets**, covering about an hour including the unfinished current bucket. They advance when a bucket boundary is crossed rather than regrouping observations on every refresh. A completed bucket keeps its observations and color when shifted left. The forming bucket may legitimately change color as new samples arrive. Green/red compare the first and last observation inside the bucket; gray means unchanged (including a single sample). Colors do not compare neighboring bars. The vertical scale still fits the visible values, so heights can move when extremes enter/leave the view. Hour/day/week/month history is unchanged.

## 11. Startup information

The build packages `assets/cli-message.txt` as console artwork. Edit that source asset and rebuild to change the banner. Startup also prints plugin/Paper/Java versions, SQLite journal mode/path, configured exchanges/tickers/item mappings, retained observations, logical time, journal recovery state, Vault provider/precision, settlement mode, QuickShop status, LuckPerms availability, report/refresh cadence and elapsed startup time. Read warning lines as well as the completion line: browsing may be available while trading is closed because no policy or provider is available.

### Default ticker selection

`default-ticker` is an optional top-level setting. The packaged value is `AGRI`; set it to any configured industry ID. An unknown ID or quoted empty string is rejected at startup and by `/qm config check`. Omitting the key selects the first configured industry in YAML order. Paper also treats a YAML null or an empty value after the colon as omitted. Changing this selection does not rename tickers, move holdings, or change item routing. Entirely custom tickers and exchange IDs are supported on a fresh installation. Run `/qm reload` to apply changes.

### Tuning commerce influence

For a small community, a starting example is `minimum-buyers: 2`, `minimum-sellers: 1`, `minimum-participants: 3`, `minimum-pairs: 2`, `max-outlook-percent: 5`, and `influence-scale: 1.0`. All requirements must pass; raising any one can prevent qualification. Low thresholds make a small group more influential. No setting permits self-trades or virtual/admin shops to qualify.

The cap affects the reference outlook, not a guaranteed price gain per transaction. Eligible units fade over one market day, target strength saturates at 384 weighted units, and the signal builds/decays with a six-market-hour half-life. Pair and participant caps remain 48 and 96 units per trailing day, respectively. Activity is bounded to 4096 retained records per ticker. A two-person market can qualify with thresholds 1 buyer, 1 seller, 2 participants and 1 pair, but its limited volume will not reach the full influence cap.

Run `/qm config check`, then `/qm reload` to apply. New keys are optional and use the documented defaults when absent. Existing evidence and the normalized smoothed signal survive restart; new thresholds govern future qualification, so an old signal decays rather than abruptly disappearing when thresholds rise. Changing the percentage or scale immediately rescales the retained signal for future simulation steps, without rewriting past prices. Setting either to zero removes applied influence. The report UI, commerce command and startup diagnostics show the configured values.

## 11. Industry stories and special events

`config.yml` controls economic behavior and assigns `industries.<TICKER>.story-collection`. `stories.yml` holds narrative content. Both files are validated at startup and by `/qm config check`; use `/qm reload` to apply. Missing files are copied from packaged resources, but existing files are never overwritten. Existing installations must add collection assignments to their own config if they want industry-specific text; an omitted assignment uses `generic`.

The bundled library contains 221 stories across 17 collections, including generic: three positive, three negative, three neutral, two special-positive and two special-negative stories per collection. Collections can be shared. These names correspond to the packaged tickers:

| Ticker | Collection | Ticker | Collection |
| --- | --- | --- | --- |
| AGRI | farming | MINE | mining |
| TIMB | forestry | FISH | fishing |
| LIVE | livestock | FOOD | food |
| FLOR | flowers | GEMS | gems |
| STON | stone | META | metals |
| WOOD | woodworking | TEXT | textiles |
| MOBS | mob_goods | REDT | redstone |
| BUIL | construction | EXPL | exploration |

An example narrative entry:

```yaml
# stories.yml
version: 1
collections:
  farming:
    special-negative:
      - id: wildfire
        headline: "Wildfires devastate farmland"
        description: "Fictional wildfires destroy fields and equipment, severely reducing expected output at {company}."
```

This is an entry example, not a complete replacement file: retain a `generic` collection and supply stories for every enabled category of each assigned collection. Explicit unknown collections fail validation. Category names are `positive`, `negative`, `neutral`, `special-positive`, `special-negative`. Every entry requires exactly `id`, `headline`, `description`. IDs and collection names use lowercase letters, digits, `_` or `-`, starting with a letter, at most 64 characters. Story IDs are unique within a collection. Only `{company}` and `{ticker}` placeholders are recognized. Text is plain text; control characters and unknown placeholders are rejected. Headlines allow 100 characters, descriptions 400; up to 100 stories per category and 128 collections. Duplicate headline/description pairs within a category are rejected.

Economics are selected before text. More stories do not change event odds, strengths or the economic random sequence. The last story ID selected in each category is excluded if alternatives exist; a one-story category necessarily repeats. Event text is resolved at creation and saved with the event. Editing a story or company name does not rewrite existing news.

### Economic controls

The following keys live under global `events:`. Override any subset under `industries.<TICKER>.events:`; omitted keys inherit the resolved global policy. `events-enabled: false` on an industry suppresses event generation, effects and news, while retaining historical records. Special events have their own enable switch.

| Key under `events` | Default | Valid range / meaning |
| --- | --- | --- |
| `max-move-percent-per-market-hour` | `0.5` | 0.01–100; time-scaled cap per sample, not an exact hourly closing-return bound |
| `response-half-life-market-hours` | about `19.46` | 0.01–8760; exact omitted default preserves the old 3.5% hourly gap response |
| `positive-weight`, `negative-weight`, `neutral-weight` | `4`, `4`, `2` | Each 0–1000; ordinary weights must total more than zero |
| `positive-impact-min-percent`, `positive-impact-max-percent` | `8`, `12` | 0–90; positive outlook range |
| `negative-impact-min-percent`, `negative-impact-max-percent` | `8`, `12` | 0–90; magnitude, automatically applied with negative sign |
| `duration-min-market-hours`, `duration-max-market-hours` | `18`, `36` | 0.01–8760; active ordinary event duration |
| `gap-min-market-hours`, `gap-max-market-hours` | `12`, `36` | 0.01–8760; gap after either type of event |
| `special.enabled` | `true` | Boolean |
| `special.chance-percent` | `1` | 0–100; one roll per eligible scheduled event per ticker |
| `special.positive-weight`, `special.negative-weight` | `1`, `1` | Each 0–1000; positive sum when special events are enabled |
| `special.positive-impact-min-percent`, `special.positive-impact-max-percent` | `20`, `40` | 0–90 |
| `special.negative-impact-min-percent`, `special.negative-impact-max-percent` | `20`, `40` | 0–90; negative magnitude |
| `special.duration-min-market-hours`, `special.duration-max-market-hours` | `12`, `36` | 0.01–8760 |
| `special.cooldown-market-days` | `14` | 0–8760; measured from the end of the last special event |
| `special.response-half-life-market-hours` | `3` | 0.01–8760; must be at most the ordinary half-life when special events are enabled |
| `special.max-move-percent-per-market-hour` | `2` | 0.01–100; must be at least the ordinary cap when special events are enabled |

Minimums cannot exceed maximums. All numeric values must be finite. Weights are relative likelihoods: 4/4/2 means 40%/40%/20% when selecting an ordinary event, regardless of catalog size. This replaces the previous two-positive/one-negative selection. Neutral adds zero event outlook; price can still move because of commerce, noise or return toward reference value. Disabling a category by weight permits its story list to be empty. Set chance to zero or special.enabled to false to prevent special events.

The first event is due six market hours after creation. Events do not overlap, interrupt one another or stack. An eligible special roll that fails produces an ordinary event; ordinary events continue during the special cooldown. More industries create more independent opportunities across the server. Duration and gap draws are rounded to market ticks and event start waits for the next price sample.

A special event accelerates movement through both its response half-life and its movement cap. With a three-hour half-life, half the remaining gap closes in three market hours before noise and the cap; this is not a promised return. After expiry, normal response/cap resume and recovery is gradual. Existing `volatility` remains a dimensionless multiplier on absolute currency noise, not a percentage. Absolute price bounds remain 1–1,000,000. Neither stories nor special events affect wallet settlement, shop items or the actual Minecraft world.

Per-industry example (add these keys to the existing industry):

```yaml
story-collection: farming
events:
  special:
    chance-percent: 2
    cooldown-market-days: 21
    negative-impact-min-percent: 25
    negative-impact-max-percent: 45
```

### News and saved state

An online subscriber receives one combined portfolio briefing with total value, cumulative profit, and each holding's value/unrealized profit. Unseen news for all held tickers is collected together, sorted by special severity first, event time, then ticker. The default four-headline limit applies per digest; extras remain queued. Stories can arrive after their event ended and are dated accordingly. Scheduled briefings include news when the shared news cooldown permits. Sold holdings and disabled event industries lose pending relevance. Login starts a fresh cursor without replaying pre-login news.

The pending queue is capped at 128 items per player, preferentially retaining specials. Evicted items are counted in an explicit overflow notice. Acknowledgement follows delivery; merely checking eligibility does not consume news. Logout/unsubscribe discards the session queue. Industry report history retains up to 512 events, accessible with Older/Newer/Latest controls; news is not a permanent personal mailbox.

Active events preserve their resolved story, effect, response half-life and movement cap across restarts/config edits. New settings govern future events; the saved next-event tick and special cooldown are retained. Both event-economics and narrative PRNG states and per-category selection memory are saved separately. SQLite remains the persistence mechanism.

**Local development snapshot reset:** this change uses a new market snapshot version. Old market snapshots fail explicitly rather than silently resetting. For this unshipped local test setup, stop Paper, move `quadstock.db` and any adjacent `quadstock.db-wal` / `quadstock.db-shm` files into a backup directory, retain your YAML files, then start Paper yourself. This resets QuadStock holdings, receipts, market history, subscriptions and settlement selection; it does not reset your external economy wallet. Re-select settlement in console. No database has been moved or deleted automatically. Do not delete only one market checkpoint while retaining economic history.


## Tab portfolio and icon resource pack

Players with committed holdings see a private portfolio footer below the normal Tab player list. It shows the combined current value and unrealized profit/loss, then shares, value and unrealized profit/loss for each owned industry. Names and icon materials come from the existing industry configuration. Green means above remaining cost basis, red below, and gray break-even at displayed cent precision; explicit signs accompany gains and losses. These are estimated market values, not executable sale quotes. Realized profit stays in the full Portfolio screen.

The Tab footer and Portfolio menu share valuation logic. Prices refresh once per wall-clock second by default; committed purchases, partial sales, full exits and reconciled commits invalidate the footer on the next server tick. Tick/server lag can delay delivery. Pending, failed and uncertain settlements never become holdings. Nothing in this presentation calls Vault or retries a payment. An empty portfolio has no QuadStock footer.

Large portfolios rotate through six rows every eight seconds by default; combined totals always include all owned industries. The standard player list and header remain available. QuadStock appends to a static existing footer. If another plugin later changes the footer, QuadStock yields for that connection instead of repeatedly overwriting it. Disable this feature when another plugin needs exclusive control of the footer. Shutdown restores the original footer only while QuadStock still owns it, and removes only its own resource pack.

### Optional administrator-supplied icons

QuadStock does not bundle Minecraft artwork, generate an icon ZIP, or export resource packs. Text portfolios work without a pack. Existing settings and previously exported server files are never rewritten or deleted.

1. Create a compatible resource pack using artwork you have permission to distribute, and host it at a direct HTTP(S) URL reachable by players.
2. Set `resource-pack.url` and its actual 40-character `sha1` in `plugins/QuadStock/tab.yml` (obtain the hash with `sha1sum your-pack.zip`). Existing URLs with a blank hash remain valid configuration: startup warns and uses text without requesting a download.
3. Restart Paper at an administrator-approved time. The plugin creates `tab.yml` only when absent. `/qm config check` validates it but does not apply changes live.
4. Players with holdings can accept the optional pack. Icons appear only after successful loading; declined, failed, discarded, or still-loading packs leave readable text. The pack is stacked alongside other server packs.

```yaml
# tab.yml (separate from the existing market config.yml)
enabled: true
refresh-seconds: 1     # 0.25–30 wall-clock seconds
rows-per-page: 6       # 1–12
page-seconds: 8        # 2–60 whole seconds
resource-pack:
  url: "https://your-host.example/quadstock-tab.zip"
  sha1: ""            # set the hosted ZIP hash; blank keeps text-only mode
```

The example hostname is a placeholder, not a deployed service. With a blank URL the live text footer still works, and no download is requested. No server launch, upload, client verification, or resource-pack acceptance is implied by a passing build.

### Custom industry icon materials

Any configured ticker/exchange remains supported. A material not included in the pack keeps its industry text and numbers and emits a startup warning; it is never silently replaced with a different industry icon. To add an icon, add your own appropriately licensed transparent image at `assets/quadstock/textures/icons/<lowercase_material>.png` inside your custom pack, and a corresponding `assets/quadstock/font/icons/<lowercase_material>.json`:

```json
{"providers":[{"type":"bitmap","file":"quadstock:icons/amethyst_shard.png","height":8,"ascent":7,"chars":["\ue000"]}]}
```

Set an explicit custom pack SHA-1 and add `icon-materials: [CHEST, AMETHYST_SHARD, ...]` in `tab.yml`, listing every font supplied by that pack. Names are uppercase Paper material names, matching each industry's existing `icon`. The list retains the legacy 17 material names when omitted for compatibility; these names do not include artwork. Custom resource packs that change inventory models/textures also need matching exported glyph images; the Tab bitmap pack cannot dynamically render arbitrary client-side models. The normal Portfolio inventory continues using the configured actual Minecraft item.

## Price-history retention

```yaml
history:
  retention-market-months: 12
```

This optional section defaults to **12 market months** (336 market days), including on existing installations whose config omits it. The supported range is 1–12 whole months. Validate changes with `/qm config check`, then apply with `/qm reload` or restart; administrator files are never automatically rewritten.

Increasing retention preserves all currently available samples and lets the history grow into the longer window. It requires no schema migration, database reset or changes to holdings/trade records. Samples already pruned by the old policy cannot be reconstructed. Decreasing retention prunes samples outside the shorter window when applied; an older plugin build with three-month retention will also prune a longer history.

Only price-history retention changes. Event history still has its separate 512-event cap. Twelve months contain at most 96,769 five-market-minute samples per industry, within the existing 100,000-sample loading guard. Larger retained histories mean larger periodic snapshots and more save work on the server thread; the database-copy compatibility check is not a live-server performance benchmark.

## Physical market displays

Administrators can attach one market display to an existing block or sign. Each display has one ticker, type, period, and fixed click action. Both enabled sign sides mirror the same configuration. Displays never execute a payment: clicking opens the existing screen and normal trading confirmation still applies.

Look at a block within six blocks and run `/qm display` to open a draft editor. Choose the ticker, type, appearance and relevant period. Left/right clicks cycle or adjust settings. **Preview** shows private temporary text without changing a sign; **Save** persists and activates the display. **Cancel**, disconnect, reload or five minutes without editing discard the draft. Floating text stays aligned with the block grid and is readable from both front and back. Facing-aware anchors supply the initial direction; other blocks use the nearest cardinal direction toward the creator. Adjust yaw in 90-degree steps, height, horizontal offsets and size to fit the build. Creation defaults to Summary/Day; signs use the front side until changed. A sign must already exist to use Sign appearance.

| Type | Visible content | Right-click opens |
| --- | --- | --- |
| Summary | Price, observed change, active-event direction | Ticker price history |
| Price | Price, observed percentage and absolute change | Trading desk |
| Chart | Compact sparkline, observed change, last observed price | Ticker price history |
| News | Most recent event headline, automatically scrolling if long | Industry reports |
| Activity | Capped shop evidence, qualification/integration status, applied influence | Industry reports |

Sneak-right-click the bound block while holding a block, item frame, glow item frame or painting to build against it without opening the display screen. Main-hand and off-hand placement are supported. While sneaking, floating text remains visible but its click target is hidden from that player so the bound block can be targeted. Normal right-click still opens the market screen. Existing anchor/support blocks cannot be replaced or merged (for example, doubling a protected slab), and other protection plugins may still deny placement.

Public text never includes player holdings or wallets. Screen access checks the same `quadstock.use`, `quadstock.trade`, and `quadstock.reports` permissions as normal navigation. Management requires `quadstock.display.admin` (operator by default and included in `quadstock.admin`).

### Time, formatting and scrolling

Hour/Day/Week/Month match the ticker UI: each of the nine chart columns spans one selected **market** period. LIVE follows nine shorter buckets across approximately one market hour, including the currently forming bucket. Observed change compares the first recorded open with the last recorded close in that view; it is not a trailing single-day return. Missing samples remain gaps. Current price can differ from the last recorded chart price. Clicking Summary or Chart preserves the selected mode.

Primary headings/prices are bold; secondary context is italic. Positive values are green, negative red, rounded-zero values gray. Signs shorten names but preserve numeric units; large figures use K/M/B magnitude suffixes. Sign headers use H/D/W/M/LIVE, and their change line has the same observed-change meaning as the UI. Floating text uses fuller labels, a dark background and timestamps. A custom client font may change fitting; the layouts target the vanilla font.

Regular signs and floating charts use block-height sparklines. Narrow hanging signs use four compact levels (`_`, `-`, `=`, `^`) so all nine positions fit. Gray gaps are `·` on full-width charts and `?` on narrow charts. Heights represent closing prices, while each point's color compares that bucket's own open/close. The scale fits the available closing prices.

Only news headlines scroll. Other values stay stationary. Scrolling preserves Unicode characters and color, pauses at both ends, and restarts for a new headline. Speed and pause controls are presentation timing in real milliseconds; they do not advance the market clock. Full news remains available by clicking. Activity is shop evidence, not stock-trading volume or a forecast that the price must rise.

### Commands and properties

```text
/qm display
/qm display create TICKER summary|price|chart|news|activity sign|floating
/qm display edit [ID]
/qm display set ID PROPERTY VALUE
/qm display remove [ID]
/qm display list [WORLD]
/qm display info [ID]
/qm display allowed
```

Omit an ID to target an existing display for edit/remove/info. IDs appear on creation and in list output and tab completion. Console supports ID-based set/remove/info, list and allowed; creation and menu editing require a player. `set` saves immediately through the same validation as the menu. A stale draft cannot overwrite a newer saved revision.

| Property | Values / limits |
| --- | --- |
| `ticker` | Any configured ticker |
| `type` | summary, price, chart, news, activity |
| `surface` | sign, floating; changing this replaces the same display |
| `period` | hour, day, week, month, live; meaningful for Summary/Price/Chart |
| `sides` | front, back, both; for signs |
| `yaw` | -180 through 180 degrees, snapped to the nearest 90 degrees; fixed floating direction |
| `height` | 0–5 blocks above anchor base |
| `x`, `z` | -3 through 3 blocks from anchor center |
| `size` | 0.25–3; floating text scale |
| `accent` | `#RRGGBB`; headings/accents, not movement colors |
| `scroll-ms` | 100–2000; default 200 per headline step |
| `pause-ms` | 500–10000; default 2000 at either end |

### Allowed anchors and protection

`config.yml` ships a `displays` section with `allowed-anchor-materials`, `max-displays: 256`, and `view-distance: 32`. Existing files without that section use the same defaults without being rewritten. Defaults include stone/stone bricks, polished andesite, deepslate bricks, quartz/smooth quartz, bricks, oak/spruce/dark oak planks, iron/gold/copper blocks, and all four oak/spruce sign forms. Add exact block material names to allow other anchors; `/qm display allowed` lists the active policy. An empty list prevents new registrations. Validate with `/qm config check`, then apply with `/qm reload`.

Admins may allow interactive anchors such as chests: registering one takes over its normal right-click, even for a player without permission to open the market screen. Air, fluids, falling blocks, pistons, TNT, beds, bisected blocks, portals, fire and unsupported non-solid blocks cannot safely act as anchors. The allowlist does not override those technical checks. Sign appearance requires a sign; stable signs may also anchor floating displays.

Registered anchors and required sign supports are protected against supported break, explosion, piston, editing and environmental event paths. Protection applies to administrators too: attempted breaks explain that the block is a QuadStock display or support, and admins receive removal guidance. Remove the display before changing its block. Support positions are persisted so they remain protected even when the sign's chunk is unloaded. Required support chunks must be loaded when saving an edit; the plugin does not load them itself. A support shared by multiple signs stays protected until no registration needs it. Unselected sign-side content remains editable; sign-wide waxing and bucket actions are blocked. Protection requires an active plugin and cannot stop direct world-file edits or plugins that bypass events.

Removing a material from the allowlist suspends existing displays of that material while preserving their definitions, original sign text and protection. Re-allowing it resumes valid displays. Rendering/animation pauses without nearby viewers. No chunk is force-loaded, and unloaded registrations stay dormant. The count setting accepts 1–2048 and view distance 8–64 blocks; these bounds are safeguards, not a production capacity claim.

### Recovery and removal

Definitions and original managed sign content are stored in SQLite. Floating text and click targets are disposable entities recreated from those definitions. Signs are marked Offline on graceful shutdown; a crash can leave the last rendered text until restart. Pending removal survives a restart or unloaded chunk.

Normal removal leaves the block intact and restores original managed sign text, color and glow. Changes to an unselected side are preserved. Changed/missing anchors are not rebuilt. Unexpected edits to owned sign text suspend updates rather than overwrite them; inspect the reason with `/qm display info ID`.

If restoring the original text is no longer wanted, `/qm display remove ID keep` explicitly releases the display while keeping the sign's current visible content. Use it to resolve an external-edit conflict without overwriting that edit. A removal against a replaced block leaves the replacement untouched. Identical replacement of an ordinary non-sign block cannot always be detected; administrative world-edit tools must be used with care around registered displays.

Automated coverage verifies configuration, storage, chart calculations, text fitting/scrolling, and sign restoration conflicts. Visual fit, facing, hit targets, event coverage on a live server and dense-market performance still require manual acceptance testing. No server launch is part of an automated build.

### Industry share supply and player holding caps

Each industry accepts two optional settings in `config.yml`:

```yaml
industries:
  YOUR_TICKER:
    # Keep this industry's other settings.
    share-supply: 100000
    max-shares-per-player: 1000
```

`share-supply` is the total number of shares players can collectively hold in this industry.
`max-shares-per-player` is the maximum holding for each player UUID in this industry, across all purchases.
Both accept `unlimited` (also the default when omitted), or whole numbers from 0 through 9223372036854775807; the maximum value is treated as unlimited. Zero prevents new purchases. The two settings are independent; a player cap may exceed supply, but purchases must satisfy both.

Available supply equals configured supply minus all committed holdings, including offline players, floored at zero. Completed buys consume it; completed sales return only the shares actually sold. Quotes reserve no shares. Failed payments change neither supply nor holdings; uncertain payments block trading until reconciliation. SQLite trade history restores holdings and availability across restarts. Limits apply in both settlement modes and are independent of reserve funding, daily payout allowances, and `trading.max-shares-per-order`.

The trading screen shows available supply and your holding cap. BUY MAX also respects both limits, wallet balance, and the order limit. An explicit buy above either limit is rejected before payment; confirmation rechecks capacity. Selling remains subject to the existing holdings and funding checks.

Edit while stopped and restart to apply; `/qm config check` validates without applying. Existing administrator files are not rewritten. Lowering either limit never removes owned shares: affected players can sell, but cannot buy beyond the new limits. Increasing supply releases additional capacity; there is no automatic issuance or daily reset.
