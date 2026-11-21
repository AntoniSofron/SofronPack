package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodLevelsTest {
    @Test void resolutionAtEveryDistance() {
        for (int distance = 0; distance <= 128; distance++) {
            int expected = distance <= 48 ? 1 : distance <= 86 ? 2 : 4;
            assertEquals(expected, ExtendedLodLevels.voxelSize(distance));
            assertEquals(4, ExtendedLodLevels.firstPaintVoxelSize(distance));
        }
    }
}
