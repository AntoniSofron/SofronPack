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

    @Test void oldOneBlockGpuMeshIsReducedAsCameraMovesAwayAndRefinedOnReturn() {
        // Four-by-four chunks around x=60. The source data does not change,
        // only the per-chunk display level selected by the moving camera.
        long near = tileLevels(15, 0, 60, 0);
        long middle = tileLevels(15, 0, 10, 0);
        long far = tileLevels(15, 0, -10, 0);
        long furthest = tileLevels(15, 0, -40, 0);
        assertEquals(0L, near, "Initial 1x1 view must contain 16 level-zero chunks");
        assertTrue(ExtendedLodLevels.requiresCoarsening(near, middle));
        assertTrue(ExtendedLodLevels.requiresCoarsening(middle, far));
        assertTrue(ExtendedLodLevels.requiresCoarsening(far, furthest));
        assertFalse(ExtendedLodLevels.requiresCoarsening(furthest, near),
                "Returning to previously visited terrain is a refinement, not a reduction");
        assertFalse(ExtendedLodLevels.requiresCoarsening(middle, middle));
    }

    @Test void mixedResolutionTileReducesEvenIfOnlyOneChunkCrossesABoundary() {
        long old = 0b01_01_00_00L;
        long target = 0b01_10_00_00L;
        assertTrue(ExtendedLodLevels.requiresCoarsening(old, target));
        assertFalse(ExtendedLodLevels.requiresCoarsening(target, old));
    }

    private static long tileLevels(int tileX, int tileZ, int cameraX, int cameraZ) {
        long packed = 0;
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            packed |= (long) ExtendedLodLevels.ringForChunk(
                    tileX * 4 + x, tileZ * 4 + z, cameraX, cameraZ) << (2 * (z * 4 + x));
        return packed;
    }

    @Test void usesSquareChunkRingsAndIgnoresCameraHeight() {
        assertEquals(0, ExtendedLodLevels.ringForChunk(40, 40, 0, 0));
        assertEquals(1, ExtendedLodLevels.ringForChunk(41, 0, 0, 0));
        assertEquals(2, ExtendedLodLevels.ringForChunk(65, 65, 0, 0));
        assertEquals(3, ExtendedLodLevels.ringForChunk(97, 97, 0, 0));
    }
}
