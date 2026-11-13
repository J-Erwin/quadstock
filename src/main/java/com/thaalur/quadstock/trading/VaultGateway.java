package com.thaalur.quadstock.trading;

import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Vault adapter. No XConomy API; replacement/removal closes this running adapter. */
public final class VaultGateway implements TradingDesk.Gateway {
  private final JavaPlugin plugin;
  private Economy bound;

  /**
   * Creates an unbound adapter; the first service lookup pins the economy instance until restart.
   */
  public VaultGateway(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  private Economy service() {
    if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault"))
      throw new IllegalStateException("Vault is unavailable");
    Economy current = plugin.getServer().getServicesManager().load(Economy.class);
    if (current == null || !current.isEnabled())
      throw new IllegalStateException("Economy provider is unavailable");
    if (bound == null) bound = current;
    if (bound != current)
      throw new IllegalStateException(
          "Economy service changed; restart and reconcile before trading");
    return current;
  }

  /**
   * Returns provider name plus implementation class; a replacement instance fails the service
   * check.
   */
  public String identity() {
    Economy e = service();
    return e.getName() + "|" + e.getClass().getName();
  }

  /** Returns 0–4 decimal places; Vault’s unrestricted value (-1) maps to two. */
  public int digits() {
    int digits = service().fractionalDigits();
    if (digits == -1) digits = 2;
    if (digits < 0 || digits > 4) throw new IllegalStateException("Unsupported economy precision");
    return digits;
  }

  /**
   * Reads an existing account balance without creating an account. Invalid or negative balances
   * fail.
   */
  public BigDecimal balance(UUID player) {
    Economy e = service();
    var account = Bukkit.getOfflinePlayer(player);
    if (!e.hasAccount(account)) throw new IllegalStateException("No economy account");
    double value = e.getBalance(account);
    if (!Double.isFinite(value) || value < 0)
      throw new IllegalStateException("Invalid economy balance");
    return BigDecimal.valueOf(value);
  }

  /**
   * Performs one withdrawal for BUY or deposit for SELL. Rejects amounts that cannot round-trip
   * through Vault’s double representation. Null/exceptional responses are uncertain.
   */
  public boolean pay(UUID player, HoldingsLedger.Side side, BigDecimal amount) {
    Economy e = service();
    var account = Bukkit.getOfflinePlayer(player);
    if (!e.hasAccount(account)) return false;
    double value = amount.doubleValue();
    if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(amount) != 0)
      throw new IllegalArgumentException("Amount cannot be represented by economy provider");
    var response =
        side == HoldingsLedger.Side.BUY
            ? e.withdrawPlayer(account, value)
            : e.depositPlayer(account, value);
    if (response == null) throw new IllegalStateException("Missing economy response");
    return response.transactionSuccess();
  }

  /**
   * Creates a Vault adapter when available, otherwise a gateway that refuses trading while allowing
   * browsing. Missing Vault classes are handled without a hard dependency.
   */
  public static TradingDesk.Gateway optional(JavaPlugin plugin) {
    try {
      if (plugin.getServer().getPluginManager().isPluginEnabled("Vault"))
        return new VaultGateway(plugin);
    } catch (LinkageError unavailable) {
      /* Browsing remains available. */
    }
    return new TradingDesk.Gateway() {
      public String identity() {
        throw new IllegalStateException("Vault is unavailable; restart after installing it");
      }

      public int digits() {
        throw new IllegalStateException("Vault is unavailable");
      }

      public BigDecimal balance(UUID player) {
        throw new IllegalStateException("Vault is unavailable");
      }

      public boolean pay(UUID player, HoldingsLedger.Side side, BigDecimal amount) {
        throw new IllegalStateException("Vault is unavailable");
      }
    };
  }
}
