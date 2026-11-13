# Command reference

All commands use `/qm` or `/quadstock`. In console, omit the slash. Optional ticker arguments use `default-ticker`, or the first configured industry when omitted. Unknown ticker IDs are rejected. Menus require a player; inspection commands can run in console.

| Command | Audience | Effect |
| --- | --- | --- |
| `market` or no argument | Player | Open industry directory |
| `ticker [TICKER]` | Player | Open price chart |
| `portfolio` / `pf` | Player | Open combined committed holdings |
| `performance [TICKER]` | Player | Open per-ticker profit/value history |
| `trade [TICKER]` | `quadstock.use` + `quadstock.trade` | Open trade selection; no payment until confirmation |
| `trade TICKER buy\|sell AMOUNT` | `quadstock.use` + `quadstock.trade` | Select a custom whole-share quantity; still requires quote confirmation |
| `preview [TICKER]` | Player | Nonbinding wallet estimate |
| `transactions` | Player | Latest seven operations across tickers |
| `reports [TICKER]` | Player | Industry reports and subscription control |
| `subscribe` / `unsubscribe` | Player | Persist personal chat preference |
| `clock` | Any sender | Logical market time |
| `status` | Any sender | Industry and settlement summaries |
| `config` | `quadstock.admin` | Active settings summary |
| `config check` | `quadstock.admin` | Validate disk settings without applying |
| `commerce` | `quadstock.commerce.inspect` | Session diagnostics, including participant UUIDs |
| `settlement` | Console only | Policy and pending-operation status |
| `settlement select MODE LIMIT` | Console only | Select or change mode/default allowance; requires a healthy journal and unchanged provider/precision |
| `settlement recover safe` | Console only | Cancel PREPARED or commit PAID without provider payment |
| `settlement recover paid EVIDENCE` | Console only | Reconcile an uncertain payment as paid, then commit |
| `settlement recover failed EVIDENCE` | Console only | Reconcile an uncertain payment as failed |

The permission defaults for administrator/commerce inspection are operator-only. Settlement administration checks the actual console sender type; having operator permission in-game is insufficient. See [recovery](../trading.md) before resolving a payment.
