package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodLevelsTest {
    @Test void fastFirstPaintIsAlwaysAtLeastFourBlockDetail() {
        assertEquals(4, ExtendedLodLevels.firstPaintVoxelSize(0));
        assertEquals(4, ExtendedLodLevels.firstPaintVoxelSize(48));
        assertEquals(4, ExtendedLodLevels.firstPaintVoxelSize(64));
        assertEquals(8, ExtendedLodLevels.firstPaintVoxelSize(96));
        assertEquals(16, ExtendedLodLevels.firstPaintVoxelSize(128));
    }

    @Test void exactDetailTransitionsFollowFiveBands() {
        assertEquals(1, ExtendedLodLevels.voxelSize(0));
        assertEquals(1, ExtendedLodLevels.voxelSize(32));
        assertEquals(2, ExtendedLodLevels.voxelSize(33));
        assertEquals(2, ExtendedLodLevels.voxelSize(48));
        assertEquals(4, ExtendedLodLevels.voxelSize(49));
        assertEquals(4, ExtendedLodLevels.voxelSize(64));
        assertEquals(8, ExtendedLodLevels.voxelSize(65));
        assertEquals(8, ExtendedLodLevels.voxelSize(96));
        assertEquals(16, ExtendedLodLevels.voxelSize(97));
        assertEquals(16, ExtendedLodLevels.voxelSize(128));
    }
}
