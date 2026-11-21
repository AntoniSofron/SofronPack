package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodFogTest {
    @Test void milestoneIntensityIsExactlySpecified() {
        assertEquals(0f, ExtendedLodFog.opacity(0), 0.00001);
        assertEquals(0.02f, ExtendedLodFog.opacity(16), 0.00001);
        assertEquals(0.13f, ExtendedLodFog.opacity(32), 0.00001);
        assertEquals(0.55f, ExtendedLodFog.opacity(64), 0.00001);
        assertEquals(0.85f, ExtendedLodFog.opacity(96), 0.00001);
        assertEquals(1f, ExtendedLodFog.opacity(128), 0.00001);
        assertEquals(1f, ExtendedLodFog.opacity(150), 0.00001);
    }

    @Test void badWeatherMovesAllBandsCloserButPreservesSmoothness() {
        assertEquals(0.02f, ExtendedLodFog.opacity(8, 1f), .0001f);
        assertEquals(.13f, ExtendedLodFog.opacity(16, 1f), .0001f);
        assertEquals(.55f, ExtendedLodFog.opacity(32, 1f), .0001f);
        assertEquals(.85f, ExtendedLodFog.opacity(56, 1f), .0001f);
        assertEquals(1f, ExtendedLodFog.opacity(64, 1f), .0001f);
        assertEquals(0f, ExtendedLodFog.strengthFromEnvironmentalEnd(2048), .0001f);
        assertEquals(1f, ExtendedLodFog.strengthFromEnvironmentalEnd(256), .0001f);
        for (float strength : new float[] {0f, .25f, .5f, 1f}) {
            float previous = -1f;
            for (int i = 0; i <= 1300; i++) {
                float now = ExtendedLodFog.opacity(i / 10d, strength);
                assertTrue(now >= previous - 1e-5f);
                previous = now;
            }
        }
    }

    @Test void fogNeverDecreasesOrJumpsAcrossLodBorders() {
        float previous = -1;
        for (int i = 0; i <= 12800; i++) {
            float opacity = ExtendedLodFog.opacity(i / 100.0);
            assertTrue(opacity >= previous - 0.000001f);
            assertTrue(opacity >= 0f && opacity <= 1f);
            previous = opacity;
        }
        for (double cut : new double[]{16, 32, 64, 96, 128}) {
            assertEquals(ExtendedLodFog.opacity(cut - .0001),
                    ExtendedLodFog.opacity(cut), 0.00005);
        }
    }
}
