package com.thaalur.quadstock.display;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.block.*;
import org.junit.Test;

public class DisplayProtectionTest {
  @Test
  public void pistonChainChecksSourceAndBothDestinations() {
    Map<Integer, Block> blocks = new HashMap<>();
    for (int i = -1; i <= 1; i++) {
      int x = i;
      blocks.put(
          i,
          (Block)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Block.class},
                  (p, m, a) -> {
                    if (m.getName().equals("getRelative"))
                      return blocks.get(x + ((BlockFace) a[0]).getModX());
                    throw new UnsupportedOperationException(m.getName());
                  }));
    }
    for (int x = -1; x <= 1; x++) {
      Block protectedOne = blocks.get(x);
      assertTrue(
          DisplayProtection.movementTouches(
              List.of(blocks.get(0)), BlockFace.EAST, b -> b == protectedOne));
    }
    assertFalse(
        DisplayProtection.movementTouches(List.of(blocks.get(0)), BlockFace.EAST, b -> false));
  }

  @Test
  public void attachedDecorationsArePlacementItems() {
    assertTrue(DisplayProtection.decoration(org.bukkit.Material.ITEM_FRAME));
    assertTrue(DisplayProtection.decoration(org.bukkit.Material.GLOW_ITEM_FRAME));
    assertTrue(DisplayProtection.decoration(org.bukkit.Material.PAINTING));
    assertFalse(DisplayProtection.decoration(org.bukkit.Material.DIAMOND_AXE));
  }

  @Test
  public void ownedEntityClicksNeverOpenWhileSneakingAndOpenOnceOtherwise() {
    for (boolean sneaking : List.of(true, false)) {
      var player =
          (org.bukkit.entity.Player)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {org.bukkit.entity.Player.class},
                  (p, m, a) -> {
                    if (m.getName().equals("isSneaking")) return sneaking;
                    throw new UnsupportedOperationException(m.getName());
                  });
      for (var hand :
          List.of(
              org.bukkit.inventory.EquipmentSlot.HAND,
              org.bukkit.inventory.EquipmentSlot.OFF_HAND)) {
        var event = new org.bukkit.event.player.PlayerInteractEntityEvent(player, null, hand);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        DisplayProtection.ownedEntityClick(event, (p, entity) -> calls.incrementAndGet());
        assertTrue(event.isCancelled());
        assertEquals(
            !sneaking && hand == org.bukkit.inventory.EquipmentSlot.HAND ? 1 : 0, calls.get());
      }
    }
  }

  private org.bukkit.event.player.PlayerInteractEvent interaction(
      org.bukkit.inventory.EquipmentSlot hand) {
    var player =
        (org.bukkit.entity.Player)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Player.class},
                (p, m, a) -> {
                  throw new UnsupportedOperationException(m.getName());
                });
    var event =
        new org.bukkit.event.player.PlayerInteractEvent(
            player,
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
            null,
            null,
            BlockFace.UP,
            hand);
    event.setUseInteractedBlock(org.bukkit.event.Event.Result.DEFAULT);
    return event;
  }

  @Test
  public void sneakPlacementLeavesVanillaItemUseAvailableForEitherHand() {
    for (var hand :
        List.of(
            org.bukkit.inventory.EquipmentSlot.HAND, org.bukkit.inventory.EquipmentSlot.OFF_HAND)) {
      var event = interaction(hand);
      assertTrue(DisplayProtection.sneakPlacement(event, true, true, false));
      assertEquals(org.bukkit.event.Event.Result.DEFAULT, event.useInteractedBlock());
      assertEquals(org.bukkit.event.Event.Result.DEFAULT, event.useItemInHand());
    }
  }

  @Test
  public void offhandPlacementSuppressesThePrecedingMainHandMenuAndItem() {
    var main = interaction(org.bukkit.inventory.EquipmentSlot.HAND);
    assertTrue(DisplayProtection.sneakPlacement(main, true, false, true));
    assertEquals(org.bukkit.event.Event.Result.DENY, main.useItemInHand());
    var off = interaction(org.bukkit.inventory.EquipmentSlot.OFF_HAND);
    assertTrue(DisplayProtection.sneakPlacement(off, true, true, false));
    assertEquals(org.bukkit.event.Event.Result.DEFAULT, off.useItemInHand());
  }

  @Test
  public void placementNeverOverridesAnotherProtectionPluginsDenial() {
    var event = interaction(org.bukkit.inventory.EquipmentSlot.HAND);
    event.setCancelled(true);
    assertTrue(DisplayProtection.sneakPlacement(event, true, true, false));
    assertEquals(org.bukkit.event.Event.Result.DENY, event.useItemInHand());
    assertEquals(org.bukkit.event.Event.Result.DENY, event.useInteractedBlock());
  }

  @Test
  public void normalClicksAndSneakingWithoutBlocksStillUseDisplayRouting() {
    var event = interaction(org.bukkit.inventory.EquipmentSlot.HAND);
    assertFalse(DisplayProtection.sneakPlacement(event, false, true, true));
    assertFalse(DisplayProtection.sneakPlacement(event, true, false, false));
    assertEquals(org.bukkit.event.Event.Result.DEFAULT, event.useItemInHand());
  }
}
