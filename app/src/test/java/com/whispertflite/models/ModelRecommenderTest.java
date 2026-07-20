package com.whispertflite.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Verifies the device-tier heuristic and the model it recommends (pure logic; no Android runtime). */
public class ModelRecommenderTest {

    private static String reco(int cores, long ramMb, boolean arm64, boolean lowRam, boolean ru) {
        return ModelRecommender.recommend(DeviceProfile.forTest(cores, ramMb, arm64, lowRam), ru).model.id;
    }

    @Test public void weakDeviceAlwaysGetsSafeBase() {
        assertEquals("gguf-base", reco(8, 8000, false, false, false)); // 32-bit
        assertEquals("gguf-base", reco(8, 2000, true, false, false));  // low RAM
        assertEquals("gguf-base", reco(4, 8000, true, false, false));  // few cores
        assertEquals("gguf-base", reco(8, 8000, true, true, false));   // isLowRamDevice
        assertEquals("gguf-base", reco(8, 8000, false, false, true));  // 32-bit even for Russian
    }

    @Test public void capableRussianGetsGigaAm() {
        assertEquals("sherpa-gigaam-ru", reco(8, 8000, true, false, true));  // strong
        assertEquals("sherpa-gigaam-ru", reco(6, 4000, true, false, true));  // mid
    }

    @Test public void capableInternationalGetsParakeet() {
        assertEquals("sherpa-parakeet-v3", reco(8, 8000, true, false, false)); // strong
        assertEquals("sherpa-parakeet-v3", reco(6, 4000, true, false, false)); // mid, still fits
    }

    @Test public void tierBoundaries() {
        assertEquals(0, DeviceProfile.forTest(8, 8000, false, false).tier()); // armv7
        assertEquals(0, DeviceProfile.forTest(4, 8000, true, false).tier());  // few cores
        assertEquals(0, DeviceProfile.forTest(8, 2000, true, false).tier());  // low RAM
        assertEquals(1, DeviceProfile.forTest(6, 4000, true, false).tier());  // mid
        assertEquals(2, DeviceProfile.forTest(8, 6000, true, false).tier());  // strong
    }

    @Test public void ramBudgetGuard() {
        DeviceProfile tiny = DeviceProfile.forTest(8, 900, true, false);
        assertFalse(ModelRecommender.fits(tiny, "sherpa-parakeet-v3")); // 640MB*1.5=960 > 900
        assertTrue(ModelRecommender.fits(tiny, "gguf-base"));           // 142MB*1.5=213 <= 900
        assertTrue(ModelRecommender.fits(DeviceProfile.forTest(8, 8000, true, false), "sherpa-parakeet-v3"));
    }
}
