# Settlement setup and recovery

Install Vault and a compatible economy provider, then explicitly select one mode in the Paper console:

```text
quadstock settlement select RESERVE_BACKED 10000
```

Or, for liquidity without reserve constraints:

```text
quadstock settlement select UNLIMITED_LIQUIDITY 0
```

Reserve mode shares funds among tickers in the same configured market. Buys add funds; sales spend funds and the ticker's daily allowance. The numeric selection is a default allowance, not initial funding. Unlimited mode can create currency through sale payouts. Provider identity and precision are pinned by policy; changing the registered provider closes trading until the mismatch is resolved. There is no XConomy dependency.

Daily payout limits apply in both settlement modes. Each industry’s explicit limit overrides the settlement default. In unlimited mode, an inherited default of `0` is uncapped; positive overrides still apply. **Existing explicit industry limits (typically `5000` in the bundled configuration) now also constrain unlimited-mode sales.** Allowance is shared across players per ticker and resets each 24,000-tick market day. Partial offers require confirmation of the offered quantity; zero-capacity sales make no wallet call. Administrator configuration, holdings and journal data are preserved; no database migration is needed.

## Uncertain payments

`quadstock settlement` lists any unresolved operation. Do not repeatedly click Confirm or restore only the holdings side of the economy.

- PREPARED means no provider attempt was recorded. `quadstock settlement recover safe` cancels it.
- PAID means success was durably recorded. The same safe command commits holdings without another payment.
- IN_FLIGHT means the outcome needs evidence. Inspect provider records and actual account changes. Use `quadstock settlement recover paid verified-provider-receipt` or `quadstock settlement recover failed verified-no-payment` only after establishing the outcome.

Recovery records the evidence note. It never calls the provider again. A failed write makes the running journal unavailable; restart before further reconciliation. Restore a matching database and economy backup together when recovery from backups is required.

## Changing modes without a reset

Run the same `settlement select MODE LIMIT` command to change mode or the inherited daily allowance. Selection is console-only, preserves existing data, invalidates all outstanding quotes and makes no wallet payment. Pending payments or an unhealthy journal must be resolved first. Provider identity and precision cannot be changed with this command.

Reserves remain the sum of committed reserve-mode buys minus reserve-mode sales, across all periods in that mode. Unlimited-mode buys and sales do not change them. A first switch from unlimited to reserve-backed therefore starts with zero reserves, funded by subsequent purchases. Returning to reserve-backed retains its earlier reserves. All committed same-day sales count against the daily allowance, including existing journal entries and sales made while uncapped. Switching modes and purchases do not reset usage. The numeric limit is a cap, not a reserve deposit.

Industry supply and per-player holding caps may limit purchases. BUY MAX respects both as well as your wallet and the order limit. The trading screen shows available supply and your cap. Completed sales return shares to industry supply; quotes do not reserve them.
