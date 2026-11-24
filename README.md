# QuadStock

A stock-market simulation for Minecraft servers. Players buy and sell shares in configurable industries, follow price charts and market news, and track their portfolios. Simulated events move prices, and optional QuickShop integration lets eligible shop activity influence the market.

## Install on your server

QuadStock targets **Paper 26.3 running Java 25**. Java is a server requirement; players do not need to install QuadStock or any client mod.

Trading requires **Vault** and a compatible economy plugin. **QuickShop-Hikari** is optional.

1. Download the plugin JAR from [Releases](https://github.com/J-Erwin/quadstock/releases/latest).
2. With the server stopped, put the JAR in its `plugins` folder alongside Vault and your economy plugin. Replace any older QuadStock JAR.
3. Start the server to generate the configuration.
4. Follow [settlement setup](docs/trading.md) to choose how sales are funded before opening trading to players.

The [administrator guide](docs/admin-guide.md) covers industries, prices, daily payout limits, permissions and physical market displays.

## Getting started in-game

Run **`/qm`** to open the market directory. Select an industry to see its price chart, then open **Trading desk** to buy or sell shares. Choose your quantity, review the quoted price and total, and confirm the trade.

Use **Portfolio** in the menu to check your holdings and transaction history, or **Reports** for industry news and report subscriptions. Hold **Tab** for a live portfolio summary.

You can navigate through the menus from `/qm`; the [command reference](docs/development/commands.md) lists shortcuts and administrator commands.

## Documentation

- [Administrator guide](docs/admin-guide.md)
- [Settlement setup and recovery](docs/trading.md)
- [Command reference](docs/development/commands.md)
- [Developer guide and building from source](docs/development/README.md)

## License

QuadStock is licensed under the [MIT License](LICENSE). Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
