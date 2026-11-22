package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassicFogProfileTest {
    @Test void classicFogStartsAtOneThirdOfOriginalDistance() {
        assertEquals(4f, ClassicFogProfile.start(12f), 0.00001f);
        assertEquals(64f, ClassicFogProfile.start(192f), 0.00001f);
        assertEquals(0f, ClassicFogProfile.start(0f), 0.00001f);
        assertEquals(-1f, ClassicFogProfile.start(-3f), 0.00001f);
    }

    @Test void disabledSentinelsArePreserved() {
        assertEquals(Float.POSITIVE_INFINITY,
                ClassicFogProfile.start(Float.POSITIVE_INFINITY));
        assertEquals(Float.NEGATIVE_INFINITY,
                ClassicFogProfile.start(Float.NEGATIVE_INFINITY));
        assertTrue(Float.isNaN(ClassicFogProfile.start(Float.NaN)));
    }

    @Test void fogEndIsNotPartOfClassicAdjustment() {
        // The original shader adjusts the start only; ends are left intact.
        float vanillaStart = 90f, vanillaEnd = 256f;
        float classicStart = ClassicFogProfile.start(vanillaStart);
        assertEquals(30f, classicStart, 0.00001f);
        assertEquals(256f, vanillaEnd, 0.00001f);
    }
}
