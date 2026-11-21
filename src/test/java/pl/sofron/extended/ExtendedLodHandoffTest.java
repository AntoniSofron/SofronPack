package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodHandoffTest {
    @Test void nearStartTracksSevenEighthsOfNormalDistance() {
        assertEquals(7f, ExtendedLodHandoff.nearStartChunks(8), 1e-6f);
        assertEquals(14f, ExtendedLodHandoff.nearStartChunks(16), 1e-6f);
        assertEquals(21f, ExtendedLodHandoff.nearStartChunks(24), 1e-6f);
        assertEquals(28f, ExtendedLodHandoff.nearStartChunks(32), 1e-6f);
        assertTrue(ExtendedLodHandoff.hidden(13.999, 16));
        assertFalse(ExtendedLodHandoff.hidden(14, 16));
        assertFalse(ExtendedLodHandoff.hidden(15, 16));
    }
}
