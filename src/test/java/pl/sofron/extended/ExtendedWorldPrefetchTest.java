package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedWorldPrefetchTest {
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
