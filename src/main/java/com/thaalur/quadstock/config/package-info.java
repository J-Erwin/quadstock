/**
 * Strict administrator configuration and persistent ticker identity validation. YAML is loaded once
 * at startup, or inspected without application by config check. The production material validator
 * needs Paper; an injected predicate supports offline tests. Units at the Java boundary are ticks
 * except refreshSeconds, which uses wall time.
 */
package com.thaalur.quadstock.config;
