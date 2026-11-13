package com.thaalur.quadstock.data;

/**
 * Observed open/high/low/close values for a price interval.
 *
 * <p>For LivingMarket, start/end are the first/last actual observation ticks, not necessarily the
 * enclosing chart-column boundaries. No interpolation is implied.
 */
public record PriceBar(
    long startTick, long endTick, double open, double high, double low, double close) {}
