package com.thaalur.quadstock.security;

import org.bukkit.command.CommandSender;

/** Bukkit permission nodes work with LuckPerms without binding to its implementation. */
public final class Permissions {
  public static final String USE = "quadstock.use";
  public static final String TRADE = "quadstock.trade";
  public static final String REPORTS = "quadstock.reports";

  private Permissions() {}

  /** Checks current permissions, including changes made while a menu was open. */
  public static boolean require(CommandSender sender, String node) {
    if (sender.hasPermission(node)) return true;
    sender.sendMessage("[QuadStock] You do not have permission: " + node);
    return false;
  }
}
