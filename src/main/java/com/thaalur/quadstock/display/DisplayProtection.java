package com.thaalur.quadstock.display;

import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;

/** Protects owned anchors/supports without cancelling unrelated explosion damage. */
public final class DisplayProtection implements Listener {
  private final DisplayService service;
  private final java.util.Map<java.util.UUID, Long> breakNotices = new java.util.HashMap<>();

  public DisplayProtection(DisplayService service) {
    this.service = service;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    if (service.protectedBlock(e.getBlock())) {
      e.setCancelled(true);
      notifyBreak(e.getPlayer(), e.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damageBlock(BlockDamageEvent e) {
    if (service.protectedBlock(e.getBlock())) {
      e.setCancelled(true);
      notifyBreak(e.getPlayer(), e.getBlock());
    }
  }

  private void notifyBreak(org.bukkit.entity.Player player, org.bukkit.block.Block block) {
    long now = System.nanoTime();
    Long previous = breakNotices.get(player.getUniqueId());
    if (previous != null && now - previous < 1_000_000_000L) return;
    breakNotices.put(player.getUniqueId(), now);
    player.sendMessage(
        breakMessage(service.at(block) != null, player.hasPermission(DisplayService.ADMIN)));
  }

  static net.kyori.adventure.text.Component breakMessage(boolean anchor, boolean admin) {
    String message =
        anchor
            ? "This block is being used as a QuadStock display and cannot be broken."
            : "This block supports a QuadStock display and cannot be broken.";
    if (admin) message += " Target the display block and use /qm display remove first.";
    return net.kyori.adventure.text.Component.text(
        message, net.kyori.adventure.text.format.NamedTextColor.YELLOW);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void burn(BlockBurnEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void fade(BlockFadeEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (service.protectedBlock(e.getBlockPlaced())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void grow(BlockGrowEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void spread(BlockSpreadEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void structure(StructureGrowEvent e) {
    e.getBlocks().removeIf(b -> service.protectedBlock(b.getBlock()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void fertilize(BlockFertilizeEvent e) {
    e.getBlocks().removeIf(b -> service.protectedBlock(b.getBlock()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityBlock(EntityChangeBlockEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void physics(BlockPhysicsEvent e) {
    if (service.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void fluid(BlockFromToEvent e) {
    if (service.protectedBlock(e.getToBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explode(EntityExplodeEvent e) {
    e.blockList().removeIf(service::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explodeBlock(BlockExplodeEvent e) {
    e.blockList().removeIf(service::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    if (service.protectedBlock(e.getBlock())
        || movementTouches(e.getBlocks(), e.getDirection(), service::protectedBlock))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void retract(BlockPistonRetractEvent e) {
    if (service.protectedBlock(e.getBlock())
        || movementTouches(e.getBlocks(), e.getDirection(), service::protectedBlock))
      e.setCancelled(true);
  }

  /** Conservatively checks both ends of piston motion, including side branches of sticky chains. */
  static boolean movementTouches(
      java.util.List<org.bukkit.block.Block> blocks,
      org.bukkit.block.BlockFace direction,
      java.util.function.Predicate<org.bukkit.block.Block> protectedBlock) {
    return blocks.stream()
        .anyMatch(
            b ->
                protectedBlock.test(b)
                    || protectedBlock.test(b.getRelative(direction))
                    || protectedBlock.test(b.getRelative(direction.getOppositeFace())));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void change(SignChangeEvent e) {
    var d = service.at(e.getBlock());
    if (d != null && d.surface() == DisplayDefinition.Surface.SIGN && d.side(e.getSide().name()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void openSign(io.papermc.paper.event.player.PlayerOpenSignEvent e) {
    var d = service.at(e.getSign().getBlock());
    if (d != null && d.surface() == DisplayDefinition.Surface.SIGN && d.side(e.getSide().name()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent e) {
    var b = e.getClickedBlock();
    if (b == null) return;
    var d = service.at(b);
    if (d == null) {
      if (service.protectedBlock(b)
          && e.getAction() == Action.RIGHT_CLICK_BLOCK
          && e.getItem() != null) {
        String item = e.getItem().getType().name();
        if (item.endsWith("_AXE")
            || item.endsWith("_SHOVEL")
            || item.endsWith("_HOE")
            || item.endsWith("BUCKET")
            || item.equals("HONEYCOMB")
            || item.equals("BONE_MEAL")) e.setCancelled(true);
      }
      return;
    }
    if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    if (e.getPlayer().isSneaking()) {
      var other =
          e.getHand() == EquipmentSlot.OFF_HAND
              ? e.getPlayer().getInventory().getItemInMainHand()
              : e.getPlayer().getInventory().getItemInOffHand();
      if (sneakPlacement(e, true, placementItem(e.getItem()), placementItem(other))) return;
    }
    if (d.surface() == DisplayDefinition.Surface.SIGN
        && b.getState() instanceof Sign
        && !d.side(targetSide(b, e.getPlayer()))) {
      if (e.getItem() != null
          && (e.getItem().getType() == Material.HONEYCOMB
              || e.getItem().getType().name().endsWith("BUCKET"))) e.setCancelled(true);
      return;
    }
    boolean permitted = e.useInteractedBlock() != Event.Result.DENY;
    e.setUseInteractedBlock(Event.Result.DENY);
    e.setUseItemInHand(Event.Result.DENY);
    if (permitted && e.getHand() == EquipmentSlot.HAND) service.click(e.getPlayer(), d);
  }

  private static boolean placementItem(org.bukkit.inventory.ItemStack item) {
    return item != null
        && !item.getType().isAir()
        && (decoration(item.getType()) || item.getType().isBlock());
  }

  static boolean decoration(Material material) {
    return material == Material.ITEM_FRAME
        || material == Material.GLOW_ITEM_FRAME
        || material == Material.PAINTING;
  }

  /**
   * Reserve sneak-block gestures for placement, including the main-hand event before an off-hand
   * placement. Leave existing item-use decisions untouched for the placing hand; never override
   * another plugin's denial. BlockPlaceEvent still protects anchors against replacement/merging.
   */
  static boolean sneakPlacement(
      PlayerInteractEvent event,
      boolean sneaking,
      boolean currentHandBlock,
      boolean otherHandBlock) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        || !sneaking
        || !(currentHandBlock || otherHandBlock)) return false;
    // Vanilla sneaking bypasses block activation. DENY here short-circuits Paper's
    // useItemOn path before the held block/decorative item can be placed.
    if (!currentHandBlock) event.setUseItemInHand(Event.Result.DENY);
    return true;
  }

  private static String targetSide(org.bukkit.block.Block b, org.bukkit.entity.Player p) {
    var data = b.getBlockData();
    var face =
        data instanceof org.bukkit.block.data.Directional directional
            ? directional.getFacing()
            : data instanceof org.bukkit.block.data.Rotatable rotated
                ? rotated.getRotation()
                : org.bukkit.block.BlockFace.NORTH;
    var offset = p.getEyeLocation().toVector().subtract(b.getLocation().add(.5, .5, .5).toVector());
    return offset.dot(face.getDirection()) >= 0 ? "FRONT" : "BACK";
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interactEntity(PlayerInteractEntityEvent e) {
    if (service.owned(e.getRightClicked())) {
      ownedEntityClick(e, service::click);
    }
  }

  static void ownedEntityClick(
      PlayerInteractEntityEvent e,
      java.util.function.BiConsumer<org.bukkit.entity.Player, org.bukkit.entity.Entity> click) {
    e.setCancelled(true);
    if (!e.getPlayer().isSneaking() && e.getHand() == EquipmentSlot.HAND)
      click.accept(e.getPlayer(), e.getRightClicked());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void sneak(PlayerToggleSneakEvent e) {
    service.hitVisibility(e.getPlayer(), e.isSneaking());
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    service.hitVisibility(e.getPlayer(), e.getPlayer().isSneaking());
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    service.hitVisibility(e.getPlayer(), e.getPlayer().isSneaking());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (service.owned(e.getEntity())) e.setCancelled(true);
  }

  @EventHandler
  public void load(ChunkLoadEvent e) {
    service.chunkLoaded(e.getChunk());
  }

  @EventHandler
  public void entities(EntitiesLoadEvent e) {
    service.cleanStale(e.getChunk());
  }

  @EventHandler
  public void unload(ChunkUnloadEvent e) {
    service.unload(e.getChunk());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    service.forgetPlayer(e.getPlayer().getUniqueId());
    breakNotices.remove(e.getPlayer().getUniqueId());
  }
}
