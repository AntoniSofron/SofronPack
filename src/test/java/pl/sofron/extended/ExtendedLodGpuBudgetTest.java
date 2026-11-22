package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodGpuBudgetTest {
    @Test void vertexBytesAreCountedNotJustTileCount() {
        assertEquals(96L, ExtendedLodGpuBudget.vertexBytesForQuads(1));
        assertEquals(12_858_624L, ExtendedLodGpuBudget.vertexBytesForQuads(133944));
        assertThrows(IllegalArgumentException.class,
                () -> ExtendedLodGpuBudget.vertexBytesForQuads(-1));
        assertFalse(ExtendedLodGpuBudget.acceptableTile(
                ExtendedLodGpuBudget.MAX_TILE_BYTES + 1));
        assertTrue(ExtendedLodGpuBudget.acceptableTile(
                ExtendedLodGpuBudget.MAX_TILE_BYTES));
    }

    @Test void memoryAndTransformLimitsAreIndependent() {
        long cap = ExtendedLodGpuBudget.MAX_VERTEX_BYTES;
        assertFalse(ExtendedLodGpuBudget.needsEviction(cap - 96, 96, 1));
        assertTrue(ExtendedLodGpuBudget.needsEviction(cap - 96, 97, 1));
        assertTrue(ExtendedLodGpuBudget.needsEviction(0, 96,
                ExtendedLodGpuBudget.MAX_RESIDENT_TILES + 1));
        assertFalse(ExtendedLodGpuBudget.needsEviction(0, 96,
                ExtendedLodGpuBudget.MAX_RESIDENT_TILES));
        assertTrue(ExtendedLodGpuBudget.MAX_DRAWN_TILES <
                ExtendedLodGpuBudget.MAX_RESIDENT_TILES);
    }
}
