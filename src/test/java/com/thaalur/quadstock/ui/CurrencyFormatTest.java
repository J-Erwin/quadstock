package com.thaalur.quadstock.ui;

import static org.junit.Assert.assertEquals;

import com.thaalur.quadstock.trading.CurrencyFormat;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.Test;

public class CurrencyFormatTest {
  @Test
  public void showsCurrencyAndGroupingWithoutLosingQuotedPrecision() {
    assertEquals("$1,250.00", CurrencyFormat.exact(new BigDecimal("1250.00")));
    assertEquals("$1,250.00", CurrencyFormat.exact(new BigDecimal("1250")));
    assertEquals("$0.00", CurrencyFormat.exact(BigDecimal.ZERO));
    assertEquals("$0.0001", CurrencyFormat.exact(new BigDecimal("0.0001")));
    assertEquals("$1,234.5678", CurrencyFormat.exact(new BigDecimal("1234.5678")));
    assertEquals(
        "$9,007,199,254,740,993.01", CurrencyFormat.exact(new BigDecimal("9007199254740993.01")));
  }

  @Test
  public void dollarFormattingDoesNotDependOnServerLocale() {
    var previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertEquals("$1,250.50", CurrencyFormat.exact(new BigDecimal("1250.50")));
    } finally {
      Locale.setDefault(previous);
    }
  }
}
