package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodLevelsTest {
    @Test void resolutionAtEveryDistance() {
        for (int distance = 0; distance <= 128; distance++) {
            int expected = distance <= 40 ? 1 : distance <= 64 ? 2
                    : distance <= 96 ? 4 : 8;
            assertEquals(expected, ExtendedLodLevels.voxelSize(distance),
                    "Incorrect XZ LOD resolution at chunk " + distance);
            assertEquals(Math.max(4, expected),
                    ExtendedLodLevels.firstPaintVoxelSize(distance));
        }
    }

    @Test void opaqueVerticalSlabsKeepFirstFortyChunksAtExactOneBlockY() {
        for (int d = 0; d <= 128; d++) {
            int expected = d <= 40 ? 1 : d <= 64 ? 2 : d <= 96 ? 4 : 8;
            assertEquals(expected, ExtendedLodLevels.opaqueSideYSize(d));
        }
    }

    @Test void eachBoundaryChangesOnlyAfterTheLastIncludedChunk() {
        int[] distance = {0, 40, 41, 64, 65, 96, 97, 128};
        int[] size = {1, 1, 2, 2, 4, 4, 8, 8};
        int[] ring = {0, 0, 1, 1, 2, 2, 3, 3};
        for (int i = 0; i < distance.length; i++) {
            assertEquals(size[i], ExtendedLodLevels.voxelSize(distance[i]));
            assertEquals(ring[i], ExtendedLodLevels.ringFor(distance[i]));
        }
    }

    @Test void movingCameraRestoresExactYAndXZWithoutWorldRegeneration() {
        // Same stored block at chunk 100: only its displayed mip changes.
        assertEquals(3, ExtendedLodLevels.ringForChunk(100, 0, 0, 0));
        assertEquals(2, ExtendedLodLevels.ringForChunk(100, 0, 10, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(100, 0, 40, 0));
        assertEquals(0, ExtendedLodLevels.ringForChunk(100, 0, 60, 0));
        assertEquals(3, ExtendedLodLevels.ringForChunk(100, 0, 0, 0));
    }

    @Test void usesSquareChunkRingsAndIgnoresCameraHeight() {
        assertEquals(0, ExtendedLodLevels.ringForChunk(40, 40, 0, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(41, 0, 0, 0));
        assertEquals(2, ExtendedLodLevels.ringForChunk(65, 65, 0, 0));
        assertEquals(3, ExtendedLodLevels.ringForChunk(97, 97, 0, 0));
    }
}
