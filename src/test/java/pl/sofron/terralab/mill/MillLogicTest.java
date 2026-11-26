package pl.sofron.terralab.mill;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static pl.sofron.terralab.mill.MillLogic.Part.*;

class MillLogicTest {
    @Test void requiresAContinuousShaftWithOneThroughThirtyTwoSegments() {
        for (int length = 1; length <= 32; length++) {
            final int n = length;
            assertTrue(MillLogic.powered(d -> d <= n ? SHAFT : d == n + 1 ? SAILS : OTHER));
        }
        assertFalse(MillLogic.powered(d -> d == 1 ? SAILS : OTHER));
        assertFalse(MillLogic.powered(d -> d <= 33 ? SHAFT : SAILS));
        assertFalse(MillLogic.powered(d -> d == 1 ? SHAFT : d == 3 ? SAILS : OTHER));
        assertFalse(MillLogic.powered(d -> SHAFT));
        assertFalse(MillLogic.powered(d -> OTHER));
    }

    @Test void inventoryNeverOverfillsAndRequiresInputAndOutputRoom() {
        assertEquals(8, MillLogic.insertAmount(0, 64));
        assertEquals(3, MillLogic.insertAmount(5, 64));
        assertEquals(0, MillLogic.insertAmount(8, 1));
        assertFalse(MillLogic.canGrind(0, 0));
        assertFalse(MillLogic.canGrind(1, 8));
        assertTrue(MillLogic.canGrind(8, 7));
    }
}
