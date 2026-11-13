package com.thaalur.quadstock.trading;

import static com.thaalur.quadstock.trading.HoldingsLedger.*;
import static com.thaalur.quadstock.trading.TradePreview.SettlementMode.*;
import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.UUID;
import org.junit.Test;

public class TradingDeskTest {
  private final UUID player = UUID.randomUUID();

  private static final class Wallet implements TradingDesk.Gateway {
    BigDecimal money = new BigDecimal("10000");
    int calls;
    boolean reject, uncertain;
    String name = "Test";

    public String identity() {
      return name;
    }

    public int digits() {
      return 2;
    }

    public BigDecimal balance(UUID p) {
      return money;
    }

    public boolean pay(UUID p, Side side, BigDecimal amount) {
      calls++;
      if (reject) return false;
      money = side == Side.BUY ? money.subtract(amount) : money.add(amount);
      if (uncertain) throw new IllegalStateException("response lost");
      return true;
    }
  }

  private TradingPolicy policy(TradePreview.SettlementMode mode, String cap) {
    return new TradingPolicy(mode, "Test", 2, new BigDecimal(cap));
  }

  private void trade(TradingDesk desk, Side side, int shares, double price, long tick)
      throws Exception {
    var q = desk.quote(player, side, shares, price, tick);
    desk.confirm(player, q.id(), tick);
  }

  @Test
  public void supplyAndPlayerCapsSurviveRestartAndSalesReturnShares() throws Exception {
    for (var mode : TradePreview.SettlementMode.values()) {
      Path dir = Files.createTempDirectory("share-limits");
      var wallet = new Wallet();
      var other = UUID.randomUUID();
      var policy = policy(mode, "10000");
      try (var ledger = HoldingsLedger.open(dir)) {
        var desk = new TradingDesk(ledger, wallet, policy, "CUSTOM", "EXCHANGE");
        desk.configureShareLimits(10, 6);
        assertEquals(6, desk.maximumShares(player, Side.BUY, 1));
        var stale = desk.quote(other, Side.BUY, 6, 1, 0);
        trade(desk, Side.BUY, 6, 1, 0);
        assertEquals(4, desk.availableShares());
        assertEquals(0, desk.maximumShares(player, Side.BUY, 1));
        assertThrows(IllegalArgumentException.class, () -> desk.quote(player, Side.BUY, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> desk.quote(other, Side.BUY, 5, 1, 0));
        assertThrows(IllegalStateException.class, () -> desk.confirm(other, stale.id(), 0));
        var q = desk.quote(other, Side.BUY, 4, 1, 0);
        desk.confirm(other, q.id(), 0);
        assertEquals(0, desk.availableShares());
        assertEquals(2, wallet.calls);
        var peer = new TradingDesk(ledger, wallet, policy, "OTHER", "EXCHANGE");
        peer.configureShareLimits(10, 6);
        assertEquals(10, peer.availableShares());
        assertEquals(6, peer.purchaseCapacity(player));
      }
      try (var ledger = HoldingsLedger.open(dir)) {
        var desk = new TradingDesk(ledger, wallet, policy, "CUSTOM", "EXCHANGE");
        desk.configureShareLimits(10, 6);
        assertEquals(0, desk.availableShares());
        trade(desk, Side.SELL, 2, 1, 1);
        assertEquals(2, desk.availableShares());
        assertEquals(2, desk.maximumShares(player, Side.BUY, 1));
        desk.configureShareLimits(3, 2);
        assertEquals(4, desk.position(player).shares());
        assertEquals(0, desk.purchaseCapacity(player));
        trade(desk, Side.SELL, 4, 1, 2);
        assertEquals(0, desk.availableShares());
        desk.configureShareLimits(20, 8);
        assertEquals(8, desk.purchaseCapacity(player));
      }
    }
  }

  @Test
  public void failedAndUncertainPaymentsDoNotConsumeSupplyUntilCommitted() throws Exception {
    Path dir = Files.createTempDirectory("share-payment");
    var wallet = new Wallet();
    var policy = policy(UNLIMITED_LIQUIDITY, "10000");
    try (var ledger = HoldingsLedger.open(dir)) {
      var desk = new TradingDesk(ledger, wallet, policy, "CUSTOM", "EXCHANGE");
      desk.configureShareLimits(5, 5);
      wallet.reject = true;
      trade(desk, Side.BUY, 5, 1, 0);
      assertEquals(5, desk.availableShares());
      wallet.reject = false;
      wallet.uncertain = true;
      assertThrows(IllegalStateException.class, () -> trade(desk, Side.BUY, 5, 1, 1));
      assertEquals(5, desk.availableShares());
      assertThrows(IllegalStateException.class, () -> desk.quote(player, Side.BUY, 1, 1, 2));
    }
    try (var ledger = HoldingsLedger.open(dir)) {
      var desk = new TradingDesk(ledger, wallet, policy, "CUSTOM", "EXCHANGE");
      desk.configureShareLimits(5, 5);
      desk.recover(2, "paid", "Provider confirms withdrawal");
      assertEquals(0, desk.availableShares());
      assertEquals(2, wallet.calls);
      var sale = desk.quote(player, Side.SELL, 2, 1, 3);
      wallet.uncertain = false;
      wallet.reject = true;
      desk.confirm(player, sale.id(), 3);
      assertEquals(0, desk.availableShares());
      desk.configureShareLimits(0, 0);
      assertEquals(0, desk.maximumShares(player, Side.BUY, 1));
      assertEquals(5, desk.maximumShares(player, Side.SELL, 1));
    }
  }

  @Test
  public void changingLimitsInvalidatesQuotesWithoutPayment() throws Exception {
    try (var ledger = HoldingsLedger.open(Files.createTempDirectory("share-config"))) {
      var wallet = new Wallet();
      var desk =
          new TradingDesk(
              ledger, wallet, policy(UNLIMITED_LIQUIDITY, "10000"), "CUSTOM", "EXCHANGE");
      var q = desk.quote(player, Side.BUY, 10, 1, 0);
      desk.configureShareLimits(5, 5);
      assertThrows(IllegalStateException.class, () -> desk.confirm(player, q.id(), 0));
      assertEquals(0, wallet.calls);
    }
  }

  @Test
  public void maximumUsesCurrentBalanceAndCommittedHoldingsWithoutPayment() throws Exception {
    try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-max"))) {
      var wallet = new Wallet();
      var desk =
          new TradingDesk(
              ledger, wallet, policy(UNLIMITED_LIQUIDITY, "10000"), "AGRI", "OVERWORLD");
      desk.configure("AGRI", "OVERWORLD", null, 1000, 25, true);
      assertEquals(25, desk.maximumShares(player, Side.BUY, 100));
      assertEquals(0, desk.maximumShares(player, Side.SELL, 100));
      wallet.money = new BigDecimal("301.00");
      assertEquals(3, desk.maximumShares(player, Side.BUY, 100));
      var quote = desk.quote(player, Side.BUY, 3, 100, 0);
      assertEquals(0, desk.maximumShares(player, Side.SELL, 100));
      assertEquals(0, wallet.calls);
      desk.confirm(player, quote.id(), 0);
      assertEquals(0, desk.maximumShares(player, Side.BUY, 100));
      assertEquals(3, desk.maximumShares(player, Side.SELL, 100));
      desk.configure("AGRI", "OVERWORLD", null, 1000, 2, true);
      assertEquals(2, desk.maximumShares(player, Side.SELL, 100));
      trade(desk, Side.SELL, 2, 100, 0);
      assertEquals(1, desk.maximumShares(player, Side.SELL, 100));
      assertEquals(2, wallet.calls);
    }
  }

  @Test
  public void bothModesSettleBuysAndSalesExactlyOnce() throws Exception {
    for (var mode : TradePreview.SettlementMode.values())
      try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-roundtrip"))) {
        var wallet = new Wallet();
        var desk = new TradingDesk(ledger, wallet, policy(mode, "10000"), "AGRI", "OVERWORLD");
        var buy = desk.quote(player, Side.BUY, 10, 100, 0);
        desk.confirm(player, buy.id(), 0);
        assertThrows(IllegalStateException.class, () -> desk.confirm(player, buy.id(), 0));
        trade(desk, Side.SELL, 5, 110, 1);
        assertEquals(2, wallet.calls);
        assertEquals(5, ledger.position(player, "AGRI").shares());
        assertEquals(0, new BigDecimal("9550").compareTo(wallet.money));
        assertEquals(0, new BigDecimal("50").compareTo(ledger.position(player, "AGRI").realized()));
        assertEquals(
            0,
            new BigDecimal(mode == RESERVE_BACKED ? "450" : "0")
                .compareTo(ledger.reserve("OVERWORLD")));
      }
  }

  @Test
  public void reserveOffersRequireAcceptanceAndRespectDailyBudgetAcrossRestart() throws Exception {
    Path dir = Files.createTempDirectory("desk-cap");
    var wallet = new Wallet();
    var policy = policy(RESERVE_BACKED, "150");
    try (var ledger = HoldingsLedger.open(dir)) {
      var desk = new TradingDesk(ledger, wallet, policy, "AGRI", "OVERWORLD");
      trade(desk, Side.BUY, 10, 100, 0);
      var sale = desk.quote(player, Side.SELL, 10, 100, 1);
      assertEquals(1, sale.offered());
      assertEquals(1, wallet.calls);
      desk.confirm(player, sale.id(), 1);
      assertThrows(IllegalArgumentException.class, () -> desk.quote(player, Side.SELL, 1, 100, 2));
    }
    try (var ledger = HoldingsLedger.open(dir)) {
      var desk = new TradingDesk(ledger, wallet, policy, "AGRI", "OVERWORLD");
      assertThrows(IllegalArgumentException.class, () -> desk.quote(player, Side.SELL, 1, 100, 3));
      trade(desk, Side.SELL, 1, 100, 24000);
      assertEquals(8, ledger.position(player, "AGRI").shares());
    }
  }

  @Test
  public void unlimitedPaysProfitWhileReserveLimitsOffer() throws Exception {
    for (var mode : TradePreview.SettlementMode.values())
      try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-profit"))) {
        var wallet = new Wallet();
        var desk = new TradingDesk(ledger, wallet, policy(mode, "10000"), "AGRI", "OVERWORLD");
        trade(desk, Side.BUY, 10, 100, 0);
        var q = desk.quote(player, Side.SELL, 10, 200, 1);
        assertEquals(mode == RESERVE_BACKED ? 5 : 10, q.offered());
        desk.confirm(player, q.id(), 1);
        if (mode == RESERVE_BACKED)
          assertThrows(
              IllegalArgumentException.class, () -> desk.quote(player, Side.SELL, 1, 200, 2));
        assertTrue(ledger.reserve("OVERWORLD").signum() >= 0);
      }
  }

  @Test
  public void staleQuotesAndChangedWalletNeverCallProvider() throws Exception {
    try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-stale"))) {
      var wallet = new Wallet();
      var desk =
          new TradingDesk(ledger, wallet, policy(RESERVE_BACKED, "10000"), "AGRI", "OVERWORLD");
      var expired = desk.quote(player, Side.BUY, 1, 100, 0);
      assertThrows(IllegalStateException.class, () -> desk.confirm(player, expired.id(), 1000));
      var boundary = desk.quote(player, Side.BUY, 1, 100, 23999);
      assertThrows(IllegalStateException.class, () -> desk.confirm(player, boundary.id(), 24000));
      var poor = desk.quote(player, Side.BUY, 1, 100, 24000);
      wallet.money = BigDecimal.ZERO;
      assertThrows(IllegalArgumentException.class, () -> desk.confirm(player, poor.id(), 24001));
      assertEquals(0, wallet.calls);
    }
  }

  @Test
  public void anotherTradeInvalidatesFundingVersion() throws Exception {
    try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-version"))) {
      var wallet = new Wallet();
      var desk =
          new TradingDesk(ledger, wallet, policy(RESERVE_BACKED, "10000"), "AGRI", "OVERWORLD");
      var old = desk.quote(UUID.randomUUID(), Side.BUY, 1, 100, 0);
      trade(desk, Side.BUY, 1, 100, 1);
      assertThrows(IllegalStateException.class, () -> desk.confirm(old.player(), old.id(), 2));
      assertEquals(1, wallet.calls);
    }
  }

  @Test
  public void explicitRejectionLeavesNoHoldingsOrReserve() throws Exception {
    for (var mode : TradePreview.SettlementMode.values())
      try (var ledger = HoldingsLedger.open(Files.createTempDirectory("desk-reject"))) {
        var wallet = new Wallet();
        wallet.reject = true;
        var desk = new TradingDesk(ledger, wallet, policy(mode, "10000"), "AGRI", "OVERWORLD");
        trade(desk, Side.BUY, 1, 100, 0);
        assertEquals(0, ledger.position(player, "AGRI").shares());
        assertFalse(ledger.blocked());
        assertEquals(0, ledger.reserve("OVERWORLD").signum());
      }
  }

  @Test
  public void ambiguousPaymentSurvivesRestartAndRecoveryNeverPaysAgain() throws Exception {
    for (var mode : TradePreview.SettlementMode.values()) {
      Path dir = Files.createTempDirectory("desk-recover");
      var wallet = new Wallet();
      wallet.uncertain = true;
      var policy = policy(mode, "10000");
      try (var ledger = HoldingsLedger.open(dir)) {
        var desk = new TradingDesk(ledger, wallet, policy, "AGRI", "OVERWORLD");
        var q = desk.quote(player, Side.BUY, 1, 100, 0);
        assertThrows(IllegalStateException.class, () -> desk.confirm(player, q.id(), 0));
        assertTrue(ledger.blocked());
      }
      try (var ledger = HoldingsLedger.open(dir)) {
        var desk = new TradingDesk(ledger, wallet, policy, "AGRI", "OVERWORLD");
        assertThrows(IllegalStateException.class, () -> desk.quote(player, Side.BUY, 1, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> desk.recover(1, "safe", ""));
        desk.recover(1, "paid", "Verified provider receipt");
        assertEquals(1, wallet.calls);
        assertEquals(1, ledger.position(player, "AGRI").shares());
        assertFalse(ledger.blocked());
      }
      try (var ledger = HoldingsLedger.open(dir)) {
        assertTrue(
            ledger.history(player).getFirst().steps().stream()
                .anyMatch(s -> s.note().contains("Verified provider receipt")));
      }
    }
  }

  @Test
  public void modeChangesPreserveMixedHistoryFundingAndInvalidateAllDeskQuotes() throws Exception {
    Path dir = Files.createTempDirectory("desk-switch");
    var wallet = new Wallet();
    var unlimited = policy(UNLIMITED_LIQUIDITY, "0");
    var reserve = policy(RESERVE_BACKED, "150");
    try (var ledger = HoldingsLedger.open(dir)) {
      unlimited.install(dir);
      var desk = new TradingDesk(ledger, wallet, unlimited, "AGRI", "OVERWORLD");
      var peer = new TradingDesk(ledger, wallet, unlimited, "ORE", "OVERWORLD");
      trade(desk, Side.BUY, 5, 100, 0);
      var old = desk.quote(player, Side.SELL, 1, 100, 1);
      var peerOld = peer.quote(player, Side.BUY, 1, 100, 1);
      int calls = wallet.calls;
      desk.validatePolicy(reserve);
      peer.validatePolicy(reserve);
      reserve.save(dir);
      desk.activate(reserve);
      peer.activate(reserve);
      assertEquals(calls, wallet.calls);
      assertThrows(IllegalStateException.class, () -> desk.confirm(player, old.id(), 1));
      assertThrows(IllegalStateException.class, () -> peer.confirm(player, peerOld.id(), 1));
      assertEquals(0, ledger.reserve("OVERWORLD").signum());
      assertThrows(IllegalArgumentException.class, () -> desk.quote(player, Side.SELL, 1, 100, 1));
      trade(desk, Side.BUY, 2, 100, 2);
      trade(desk, Side.SELL, 1, 100, 3);
      desk.validatePolicy(unlimited);
      unlimited.save(dir);
      desk.activate(unlimited);
      trade(desk, Side.SELL, 1, 500, 4);
      desk.validatePolicy(reserve);
      reserve.save(dir);
      desk.activate(reserve);
      assertEquals(0, new BigDecimal("100").compareTo(ledger.reserve("OVERWORLD")));
      assertEquals(0, new BigDecimal("50").compareTo(desk.remaining(4)));
      assertEquals(5, desk.position(player).shares());
      assertEquals(UNLIMITED_LIQUIDITY, ledger.entries().getFirst().intent().mode());
    }
    try (var ledger = HoldingsLedger.open(dir)) {
      assertEquals(reserve, TradingPolicy.load(dir).orElseThrow());
      var desk =
          new TradingDesk(
              ledger, wallet, TradingPolicy.load(dir).orElseThrow(), "AGRI", "OVERWORLD");
      assertEquals(5, desk.position(player).shares());
      assertEquals(0, new BigDecimal("500").compareTo(desk.position(player).cost()));
      assertEquals(0, new BigDecimal("400").compareTo(desk.position(player).realized()));
      assertEquals(0, new BigDecimal("50").compareTo(desk.remaining(5)));
      var raised = policy(RESERVE_BACKED, "200");
      desk.validatePolicy(raised);
      raised.save(dir);
      desk.activate(raised);
      trade(desk, Side.SELL, 1, 100, 5);
      assertEquals(0, ledger.reserve("OVERWORLD").signum());
      assertEquals(0, desk.remaining(5).signum());
    }
  }

  @Test
  public void policyChangesRejectPendingPaymentAndProviderOrPrecisionChanges() throws Exception {
    Path dir = Files.createTempDirectory("desk-switch-blocked");
    var original = policy(UNLIMITED_LIQUIDITY, "0");
    original.install(dir);
    try (var ledger = HoldingsLedger.open(dir)) {
      var wallet = new Wallet();
      var desk = new TradingDesk(ledger, wallet, original, "AGRI", "OVERWORLD");
      var replacement = new TradingPolicy(RESERVE_BACKED, "Replacement", 2, new BigDecimal("100"));
      wallet.name = "Replacement";
      assertThrows(IllegalStateException.class, () -> desk.validatePolicy(replacement));
      wallet.name = "Test";
      var precision = new TradingPolicy(RESERVE_BACKED, "Test", 3, new BigDecimal("100"));
      assertThrows(IllegalStateException.class, () -> desk.validatePolicy(precision));
      wallet.uncertain = true;
      assertThrows(IllegalStateException.class, () -> trade(desk, Side.BUY, 1, 100, 0));
      var reserve = policy(RESERVE_BACKED, "100");
      assertThrows(IllegalStateException.class, () -> desk.validatePolicy(reserve));
      assertThrows(IllegalStateException.class, () -> desk.activate(reserve));
      assertEquals(original, TradingPolicy.load(dir).orElseThrow());
      assertEquals(original, desk.policy());
      assertEquals(1, wallet.calls);
      assertEquals(State.IN_FLIGHT, ledger.pending().orElseThrow().state());
    }
  }

  @Test
  public void policyMustBeExplicitAndProviderMustMatch() throws Exception {
    Path dir = Files.createTempDirectory("desk-policy");
    var policy = policy(RESERVE_BACKED, "10000");
    assertTrue(TradingPolicy.load(dir).isEmpty());
    policy.install(dir);
    assertEquals(policy, TradingPolicy.load(dir).orElseThrow());
    assertThrows(java.io.IOException.class, () -> policy.install(dir));
    try (var ledger = HoldingsLedger.open(dir)) {
      var wallet = new Wallet();
      var desk = new TradingDesk(ledger, wallet, null, "AGRI", "OVERWORLD");
      assertThrows(IllegalStateException.class, () -> desk.quote(player, Side.BUY, 1, 100, 0));
      desk.activate(policy);
      wallet.name = "Replacement";
      assertThrows(IllegalStateException.class, () -> desk.quote(player, Side.BUY, 1, 100, 0));
      assertEquals(0, wallet.calls);
    }
  }
}
