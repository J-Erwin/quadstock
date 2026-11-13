/**
 * Paper inventory presentation and explicit trade confirmation. Controllers are ticker-scoped; each
 * inventory records its owner. Rendering and refresh remain on the server thread and must never
 * execute payments. Chart range/page state is captured in render callbacks; refresh cadence is
 * independent of market time.
 */
package com.thaalur.quadstock.ui;
