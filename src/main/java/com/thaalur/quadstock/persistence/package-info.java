/**
 * SQLite persistence boundaries. Intents and transitions are normalized; named checkpoints contain
 * simulation and settings state. Database commits precede in-memory publication and external
 * payment calls.
 */
package com.thaalur.quadstock.persistence;
