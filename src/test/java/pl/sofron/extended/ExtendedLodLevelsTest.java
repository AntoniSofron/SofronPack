package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodLevelsTest {
    @Test void resolutionAtEveryDistance() {
        for (int distance = 0; distance <= 128; distance++) {
            int expected = distance <= 48 ? 1 : distance <= 96 ? 2 : 4;
            assertEquals(expected, ExtendedLodLevels.voxelSize(distance));
            assertEquals(4, ExtendedLodLevels.firstPaintVoxelSize(distance));
        }
    }

    @Test void exactBoundariesAndCameraMovement() {
        assertEquals(1, ExtendedLodLevels.voxelSize(48));
        assertEquals(2, ExtendedLodLevels.voxelSize(49));
        assertEquals(2, ExtendedLodLevels.voxelSize(96));
        assertEquals(4, ExtendedLodLevels.voxelSize(97));
        assertEquals(4, ExtendedLodLevels.voxelSize(128));

        // A fixed world chunk transitions back to exact detail when the
        // player approaches, without changing/regenerating its source data.
        assertEquals(2, ExtendedLodLevels.ringForChunk(100, 0, 0, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(100, 0, 4, 0));
        assertEquals(0, ExtendedLodLevels.ringForChunk(100, 0, 52, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(100, 0, 4, 0));

        // The range test follows square chunk rings, not vertical height.
        assertEquals(0, ExtendedLodLevels.ringForChunk(48, 48, 0, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(49, 0, 0, 0));
        assertEquals(2, ExtendedLodLevels.ringForChunk(97, 97, 0, 0));
    }
}
