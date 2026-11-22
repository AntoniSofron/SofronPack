package pl.sofron.extended;

import pl.sofron.extended.client.ExtendedArenaRanges;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedArenaRangesTest {
    @Test void fragmentationIsReusedAndAdjacentRangesCoalesce() {
        var arena = new ExtendedArenaRanges(480);
        int a = arena.allocate(96);
        int b = arena.allocate(144);
        int c = arena.allocate(96);
        assertEquals(0, a);
        assertEquals(96, b);
        assertEquals(240, c);
        arena.release(b, 144);
        assertEquals(96, arena.allocate(48));
        arena.release(96, 48);
        arena.release(a, 96);
        arena.release(c, 96);
        assertEquals(480, arena.freeBytes());
        assertTrue(arena.isEmpty());
        assertEquals(0, arena.allocate(480));
    }

    @Test void exhaustedAndIllegalRangesAreRejected() {
        var arena = new ExtendedArenaRanges(96);
        assertEquals(0, arena.allocate(96));
        assertEquals(-1, arena.allocate(48));
        assertThrows(IllegalArgumentException.class, () -> arena.release(-1, 48));
        arena.release(0, 96);
        assertThrows(IllegalStateException.class, () -> arena.release(0, 48));
        assertThrows(IllegalArgumentException.class, () -> arena.allocate(0));
    }
}
