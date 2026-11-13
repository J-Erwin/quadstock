package com.thaalur.quadstock.display;

import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Per-player visibility of disposable click targets; never hides display text. */
final class DisplayHitTargets {
  private DisplayHitTargets() {}

  static void visibility(
      Plugin plugin, Player player, Iterable<Interaction> targets, boolean sneaking) {
    for (Interaction target : targets) {
      if (!target.getWorld().equals(player.getWorld())) continue;
      if (sneaking) player.hideEntity(plugin, target);
      else player.showEntity(plugin, target);
    }
  }
}
