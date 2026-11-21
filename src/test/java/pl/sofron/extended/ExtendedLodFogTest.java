package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodFogTest {
    @Test void milestoneIntensityIsExactlySpecified() {
        assertEquals(0f, ExtendedLodFog.opacity(15.99), 0.00001);
        assertEquals(0f, ExtendedLodFog.opacity(16), 0.00001);
        assertEquals(0.13f, ExtendedLodFog.opacity(32), 0.00001);
        assertEquals(0.55f, ExtendedLodFog.opacity(64), 0.00001);
        assertEquals(0.85f, ExtendedLodFog.opacity(96), 0.00001);
        assertEquals(1f, ExtendedLodFog.opacity(128), 0.00001);
        assertEquals(1f, ExtendedLodFog.opacity(150), 0.00001);
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
