/**
 * Logical market calendar, recorded simulation and chart aggregation. Historical columns are
 * half-open intervals; the final live column includes its endpoint. Missing prices remain gaps.
 * Simulation snapshots are checkpoints in the shared SQLite database.
 */
package com.thaalur.quadstock.data;
