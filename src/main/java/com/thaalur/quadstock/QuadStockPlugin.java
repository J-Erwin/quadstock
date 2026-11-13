package com.thaalur.quadstock;

import com.thaalur.quadstock.runtime.MarketRuntime;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point. Owns one runtime and delegates lifecycle without application logic. */
public final class QuadStockPlugin extends JavaPlugin {
  private MarketRuntime runtime;

  @Override
  public void onEnable() {
    runtime = new MarketRuntime(this);
    runtime.start();
  }

  /** Reloads configuration and runtime state on the server thread, never plugin bytecode. */
  public void reloadMarket() throws Exception {
    if (runtime == null || !runtime.initialized())
      throw new IllegalStateException("QuadStock is not running; restart the server.");
    var previous = runtime;
    try {
      com.thaalur.quadstock.runtime.ReloadOperation.run(
          previous::validateReload,
          previous::checkpointReload,
          previous::closeForReload,
          () -> {
            runtime = new MarketRuntime(this);
            runtime.start();
            if (!isEnabled() || runtime == null || !runtime.initialized())
              throw new IllegalStateException(
                  "Replacement runtime failed; see console and restart after correcting the"
                      + " error.");
          },
          () -> getServer().getPluginManager().disablePlugin(this));
    } catch (Exception failure) {
      getLogger().log(java.util.logging.Level.WARNING, "QuadStock reload failed", failure);
      throw failure;
    }
  }

  @Override
  public void onDisable() {
    if (runtime != null) {
      runtime.close();
      runtime = null;
    }
  }
}
