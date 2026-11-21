package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedWorldPrefetchTest {
    @Test void oneHundredTwentyEightChunkSquareIsCompleteAndUnique() {
        long[] offsets = ExtendedWorldPrefetch.spiralOffsets(128);
        assertEquals(257 * 257, offsets.length);
        Set<Long> unique = new HashSet<>();
        int lastRing = 0;
        for (long packed : offsets) {
            assertTrue(unique.add(packed));
            int x = (int)(packed >> 32), z = (int)packed;
            int ring = Math.max(Math.abs(x), Math.abs(z));
            assertTrue(ring >= lastRing && ring <= 128);
            lastRing = ring;
        }
        assertTrue(unique.contains(ExtendedWorldPrefetch.pack(-128, 128)));
        assertTrue(unique.contains(ExtendedWorldPrefetch.pack(128, -128)));
    }

    @Test void continuousFlightEventuallyRestartsEightChunkPriority() {
        assertTrue(ExtendedLodStages.shouldRecenter(0, 0,
                Integer.MIN_VALUE, Integer.MIN_VALUE));
        for (int step = 1; step < 8; step++)
            assertFalse(ExtendedLodStages.shouldRecenter(step, 0, 0, 0));
        assertTrue(ExtendedLodStages.shouldRecenter(8, 0, 0, 0));
        assertTrue(ExtendedLodStages.shouldRecenter(0, -8, 0, 0));
        assertFalse(ExtendedLodStages.shouldRecenter(12, 5, 8, 0));
        assertTrue(ExtendedLodStages.shouldRecenter(16, 5, 8, 0));
    }

    @Test void sevenStrictStagesCoverEntireSquareWithoutOverlap() {
        int[] expected = {8, 16, 32, 48, 64, 96, 128};
        long[] offsets = ExtendedWorldPrefetch.spiralOffsets(128);
        int lastEnd = 0;
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ExtendedLodStages.radius(i));
            assertEquals(lastEnd, ExtendedLodStages.startInclusive(i));
            int end = ExtendedLodStages.endExclusive(i);
            for (int j = lastEnd; j < end; j++) {
                long pos = offsets[j];
                int radius = Math.max(Math.abs((int)(pos >> 32)), Math.abs((int)pos));
                assertTrue(radius <= expected[i], "Out-of-stage chunk " + radius);
                if (i > 0) assertTrue(radius > expected[i - 1], "Previous stage leaked");
                assertEquals(i, ExtendedLodStages.indexForRadius(radius));
            }
            lastEnd = end;
        }
        assertEquals(offsets.length, lastEnd);
    }

    @Test void sixtyFourChunkSquareIsCompleteAndUnique() {
        long[] offsets = ExtendedWorldPrefetch.spiralOffsets(64);
        assertEquals(129 * 129, offsets.length);
        assertEquals(ExtendedWorldPrefetch.pack(0, 0), offsets[0]);
        Set<Long> unique = new HashSet<>();
        int previousRing = 0;
        for (long location : offsets) {
            assertTrue(unique.add(location), "Duplicate distant chunk offset");
            int x = (int)(location >> 32), z = (int)location;
            int ring = Math.max(Math.abs(x), Math.abs(z));
            assertTrue(ring <= 64 && ring >= previousRing);
            previousRing = ring;
        }
        assertTrue(unique.contains(ExtendedWorldPrefetch.pack(-64, 64)));
        assertTrue(unique.contains(ExtendedWorldPrefetch.pack(64, -64)));
    }
}
