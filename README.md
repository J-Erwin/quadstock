# QuadStock

QuadStock is a configurable Minecraft stock-market simulation. Players explore price history, trade through their server economy, track holdings and subscribe to private portfolio reports. Simulated events and eligible shop activity influence industry prices.

## Requirements

- **Paper 26.3 and Java 25**
- **Vault and a compatible economy provider** for trading
- **QuickShop-Hikari** (optional) for shop activity to influence industries

Market browsing and simulation work without an economy provider.

## Build and install

Build with a Java 25 JDK:

```sh
./gradlew clean build
```

1. With the server stopped, place `build/libs/quadstock-1.0.0.jar` in Paper's `plugins` directory. Keep only one QuadStock JAR installed.
2. Start the server to generate `plugins/QuadStock/config.yml`.
3. Before trading, choose a settlement mode in the server console. Follow [settlement setup](docs/trading.md) to choose between reserve-backed and unlimited liquidity. Daily payout limits apply in both modes.
4. Open `/qm` to browse the market.

See the [administrator guide](docs/admin-guide.md) for configuration, permissions and physical market displays.

## Play

- `/qm` — browse industries and their linked items.
- `/qm trade MINE` — review and confirm a purchase or sale.
- `/qm portfolio` — view holdings and profit.
- `/qm subscribe` and `/qm unsubscribe` — manage private portfolio reports.

Hold Tab for a live portfolio summary. `MINE` is an example ticker; use one configured on your server. See the [command reference](docs/development/commands.md) for charts, transaction history and other commands.

## Documentation

- [Administrator guide](docs/admin-guide.md)
- [Settlement setup and recovery](docs/trading.md)
- [Developer guide](docs/development/README.md)
- [Command reference](docs/development/commands.md)

## License

QuadStock is licensed under the [MIT License](LICENSE). Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
