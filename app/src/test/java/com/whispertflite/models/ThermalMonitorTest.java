package com.whispertflite.models;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure thermal-status -> throttling decision (no Android runtime). Values mirror PowerManager's
 * THERMAL_STATUS_* ladder: 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN.
 * Throttling starts at SEVERE (3).
 */
public class ThermalMonitorTest {

    @Test public void belowSevereIsNotThrottling() {
        assertFalse(ThermalMonitor.isThrottling(0)); // NONE
        assertFalse(ThermalMonitor.isThrottling(1)); // LIGHT
        assertFalse(ThermalMonitor.isThrottling(2)); // MODERATE
    }

    @Test public void severeAndAboveIsThrottling() {
        assertTrue(ThermalMonitor.isThrottling(3)); // SEVERE
        assertTrue(ThermalMonitor.isThrottling(4)); // CRITICAL
        assertTrue(ThermalMonitor.isThrottling(5)); // EMERGENCY
        assertTrue(ThermalMonitor.isThrottling(6)); // SHUTDOWN
    }
}
