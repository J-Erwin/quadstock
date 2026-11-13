/**
 * Durable opt-in preferences and session-only private report delivery. Mutable state belongs to the
 * server thread. Login/restart creates a fresh delivery interval; no backlog is replayed. Combined
 * reports read committed positions across tickers.
 */
package com.thaalur.quadstock.notifications;
