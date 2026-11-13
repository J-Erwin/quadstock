package com.thaalur.quadstock.command;

import com.thaalur.quadstock.runtime.ReloadOperation;
import org.bukkit.command.CommandSender;

/** Permission and syntax boundary for administrator runtime reloads. */
final class ReloadCommand {
  private ReloadCommand() {}

  static boolean execute(CommandSender sender, String[] args, ReloadOperation.Step reload) {
    if (!sender.hasPermission("quadstock.admin")) {
      sender.sendMessage("Reload requires quadstock.admin.");
      return true;
    }
    if (args.length != 1) {
      sender.sendMessage("Usage: /qm reload");
      return true;
    }
    try {
      reload.run();
      sender.sendMessage("QuadStock reloaded. Open menus and pending quotes were cleared.");
    } catch (Exception failure) {
      sender.sendMessage("QuadStock reload failed: " + failure.getMessage());
    }
    return true;
  }
}
