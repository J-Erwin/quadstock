package com.thaalur.quadstock.display;

import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Direct commands share the same validation/commit service as draft menus. */
public final class DisplayCommands {
  private final DisplayService service;

  public DisplayCommands(DisplayService service) {
    this.service = service;
  }

  private Player player(CommandSender sender) {
    if (sender instanceof Player p) return p;
    throw new IllegalArgumentException("This action requires a player target");
  }

  private DisplayDefinition resolve(CommandSender sender, String[] args) {
    if (args.length < 3) return service.target(player(sender));
    var d = service.get(UUID.fromString(args[2]));
    if (d == null) throw new IllegalArgumentException("Unknown display ID");
    return d;
  }

  public boolean execute(CommandSender sender, String[] args) {
    if (!sender.hasPermission(DisplayService.ADMIN)) {
      sender.sendMessage("Requires " + DisplayService.ADMIN);
      return true;
    }
    try {
      String action = args.length < 2 ? "menu" : args[1].toLowerCase(Locale.ROOT);
      switch (action) {
        case "menu" -> {
          if (args.length != 1) throw new IllegalArgumentException("Use /qm display");
          var p = player(sender);
          var b = p.getTargetBlockExact(6);
          if (b == null) throw new IllegalArgumentException("Look at a block within six blocks");
          var d = service.at(b);
          if (d == null)
            d =
                service.draft(
                    p,
                    service.tickers().iterator().next(),
                    b.getState() instanceof org.bukkit.block.Sign
                        ? DisplayDefinition.Surface.SIGN
                        : DisplayDefinition.Surface.FLOATING);
          service.editor().open(p, d);
        }
        case "create" -> {
          if (args.length != 5)
            throw new IllegalArgumentException("/qm display create TICKER TYPE sign|floating");
          var d =
              service
                  .draft(
                      player(sender),
                      args[2].toUpperCase(Locale.ROOT),
                      DisplayDefinition.Surface.valueOf(args[4].toUpperCase(Locale.ROOT)))
                  .option("type", args[3]);
          service.save(d);
          sender.sendMessage(
              "Display created: " + d.id() + ". Its right-click now opens the market screen.");
        }
        case "edit" -> {
          if (args.length > 3) throw new IllegalArgumentException("/qm display edit [ID]");
          service.editor().open(player(sender), resolve(sender, args));
        }
        case "set" -> {
          if (args.length != 5)
            throw new IllegalArgumentException("/qm display set ID PROPERTY VALUE");
          var d = resolve(sender, args).option(args[3].toLowerCase(Locale.ROOT), args[4]);
          service.save(d);
          sender.sendMessage("Display saved: " + d.id());
        }
        case "remove" -> {
          if (args.length > 4 || args.length == 4 && !args[3].equalsIgnoreCase("keep"))
            throw new IllegalArgumentException("/qm display remove [ID] [keep]");
          var d = resolve(sender, args);
          service.remove(d, args.length == 4);
          sender.sendMessage(
              "Removal requested: " + d.id() + "; use info/list for pending cleanup");
        }
        case "info" -> {
          if (args.length > 3) throw new IllegalArgumentException("/qm display info [ID]");
          var d = resolve(sender, args);
          sender.sendMessage(d.id() + " · " + service.status(d.id()) + " · " + d.anchor());
          sender.sendMessage(d.options().toString());
        }
        case "list" -> {
          if (args.length > 3) throw new IllegalArgumentException("/qm display list [WORLD]");
          for (var d : service.all()) {
            var w = org.bukkit.Bukkit.getWorld(d.anchor().world());
            String name = w == null ? d.anchor().world().toString() : w.getName();
            if (args.length == 3
                && !args[2].equalsIgnoreCase(name)
                && !args[2].equals(d.anchor().world().toString())) continue;
            sender.sendMessage(
                d.id()
                    + " · "
                    + name
                    + " · "
                    + d.ticker()
                    + " "
                    + d.type()
                    + " · "
                    + service.status(d.id()));
          }
          sender.sendMessage(
              service.all().size() + " saved displays; limit " + service.policy().maximum());
        }
        case "allowed" ->
            sender.sendMessage("Allowed anchors: " + String.join(", ", service.policy().allowed()));
        default -> sender.sendMessage("/qm display [create|edit|set|remove|list|info|allowed]");
      }
    } catch (Exception e) {
      sender.sendMessage("Display: " + e.getMessage());
    }
    return true;
  }

  public List<String> complete(CommandSender sender, String[] args) {
    if (!sender.hasPermission(DisplayService.ADMIN)) return List.of();
    Collection<String> options = List.of();
    if (args.length == 2)
      options = List.of("create", "edit", "set", "remove", "list", "info", "allowed");
    else if (args.length == 3 && args[1].equalsIgnoreCase("create")) options = service.tickers();
    else if (args.length == 3
        && Set.of("edit", "set", "remove", "info").contains(args[1].toLowerCase(Locale.ROOT)))
      options = service.all().stream().map(d -> d.id().toString()).toList();
    else if (args.length == 4 && args[1].equalsIgnoreCase("create"))
      options = Arrays.stream(DisplayDefinition.Type.values()).map(Enum::name).toList();
    else if (args.length == 5 && args[1].equalsIgnoreCase("create"))
      options = List.of("sign", "floating");
    else if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
      var keys = new ArrayList<>(DisplayDefinition.DEFAULTS.keySet());
      keys.add("ticker");
      options = keys;
    } else if (args.length == 5 && args[1].equalsIgnoreCase("set"))
      options =
          switch (args[3]) {
            case "ticker" -> service.tickers();
            case "type" -> Arrays.stream(DisplayDefinition.Type.values()).map(Enum::name).toList();
            case "period" ->
                Arrays.stream(DisplayDefinition.Mode.values()).map(Enum::name).toList();
            case "surface" -> List.of("sign", "floating");
            case "sides" -> List.of("front", "back", "both");
            default -> List.of();
          };
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    return options.stream()
        .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix))
        .sorted()
        .toList();
  }
}
