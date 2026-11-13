package com.thaalur.quadstock.trading;

import java.math.BigDecimal;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, read-only Vault boundary. Never creates accounts or moves money. */
public final class WalletPreview {
  /** Read-only wallet result. problem is null when available; balance is null on failure. */
  public record Result(String provider, int digits, BigDecimal balance, String problem) {
    public boolean available() {
      return problem == null;
    }
  }

  private WalletPreview() {}

  /**
   * Reads an existing player account on the server thread; provider failures become an unavailable
   * result. No account creation or payment is performed.
   */
  public static Result read(JavaPlugin plugin, Player player) {
    if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault"))
      return unavailable("Vault is unavailable");
    try {
      return VaultReader.read(plugin, player);
    } catch (RuntimeException | LinkageError e) {
      return unavailable("Economy provider is unavailable");
    }
  }

  private static Result unavailable(String reason) {
    return new Result("Unavailable", 2, null, reason);
  }

  // This class is resolved only when Vault is present. The core model knows nothing about a
  // provider.
  private static final class VaultReader {
    private static Result read(JavaPlugin plugin, Player player) {
      var economy =
          plugin.getServer().getServicesManager().load(net.milkbowl.vault.economy.Economy.class);
      if (economy == null || !economy.isEnabled())
        return unavailable("No enabled Vault economy provider");
      if (!economy.hasAccount(player)) return unavailable("No economy account yet");
      int digits = economy.fractionalDigits();
      if (digits == -1)
        digits = 2; // Explicit fallback for providers advertising unrestricted precision.
      if (digits < 0 || digits > 4)
        return unavailable("Provider currency precision is unsupported");
      double balance = economy.getBalance(player);
      if (!Double.isFinite(balance) || balance < 0)
        return unavailable("Provider returned an invalid or negative balance");
      return new Result(economy.getName(), digits, BigDecimal.valueOf(balance), null);
    }
  }
}
