/**
 * Quotes, settlement coordination, holdings journal and read-only valuation. All coordinated
 * operations run on the server thread, including Vault calls. Individual synchronized methods do
 * not make a cross-desk payment protocol safe for concurrent callers. Money uses BigDecimal; only
 * display/simulation marks use doubles. A quote reserves no funds, and only committed journal
 * entries affect positions. Uncertain payments block all settlement until an operator reconciles
 * them without repeating the provider call.
 */
package com.thaalur.quadstock.trading;
