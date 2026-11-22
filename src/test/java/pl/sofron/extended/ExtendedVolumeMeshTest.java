package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedVolumeMeshTest {
    @Test void separatedFloatingBlocksKeepTheirUndersidesAndGap() {
        short[] cells = new short[4096];
        cells[3 * 256 + 5 * 16 + 5] = cells[12 * 256 + 5 * 16 + 5] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, -64, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = new ExtendedVolumeMesh().build(chunks, new int[36], (name, face) -> white);
        assertNotNull(mesh);
        assertEquals(12, mesh.quads());
    }

    @Test void detailedForestIsRetainedBeyondOneIndexBatch() {
        // Isolated blocks prevent greedy merges and exceed 32,768 faces in one chunk.
        short[] cells = new short[48 * 256];
        for (int y = 0; y < 48; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (((x+y+z) & 1) == 0) cells[y*256+z*16+x] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, -16, 48, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:oak_log[axis=y]"}, cells);
        var log = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = new ExtendedVolumeMesh().build(chunks, new int[36], (name, face) -> log);
        assertEquals(cells.length / 2 * 6, mesh.quads());
    }

    @Test void capturedExactBlocksCanBeDisplayedWithDifferentMipResolutions() {
        short[] exact = new short[16 * 16 * 4];
        for (int z = 0; z < 16; z += 2) {
            for (int x = 0; x < 16; x += 2) {
                exact[(2 * 16 + z) * 16 + x] = 1;
            }
        }
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        var stored = ExtendedVoxelStore.encode(0, 0, 0, 4, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, exact);
        chunks[7] = stored;
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesher = new ExtendedVolumeMesh();
        int[] levels = new int[36];
        var full = mesher.build(chunks, levels, (name, face) -> white);
        levels[7] = 1;
        var medium = mesher.build(chunks, levels, (name, face) -> white);
        levels[7] = 2;
        var far = mesher.build(chunks, levels, (name, face) -> white);
        levels[7] = 3;
        var furthest = mesher.build(chunks, levels, (name, face) -> white);
        assertTrue(full.quads() > medium.quads(),
                "2x2 distance mip must emit fewer faces than exact 1x1");
        assertTrue(medium.quads() >= far.quads(),
                "4x4 distance mip must not exceed 2x2");
        assertTrue(far.quads() >= furthest.quads(),
                "8x8 distance mip must not exceed 4x4");
        assertArrayEquals(exact, stored.unpack(),
                "Rendering with coarser mips must never change saved 1x1 data");
    }

    @Test void distantOpaqueSideSlabsReduceQuadsButExactNearYAndWaterRemain() {
        short[] cells = new short[16 * 16 * 16];
        // Isolated opaque blocks at alternating heights expose six faces.
        for (int y = 0; y < 16; y += 2) cells[y * 256 + 5 * 16 + 5] = 1;
        // Sea-level water remains in its own one-block-high side pass.
        cells[3 * 256 + 7 * 16 + 7] = 2;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 60, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone", "minecraft:water[level=0]"}, cells);
        var mesher = new ExtendedVolumeMesh();
        int[] levels = new int[36];
        ExtendedVoxelMesher.MaterialResolver mat = (name, face) ->
                new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff,
                        name.startsWith("minecraft:water"));
        var exact = mesher.build(chunks, levels, mat);
        levels[7] = 1;
        var medium = mesher.build(chunks, levels, mat);
        levels[7] = 2;
        var far = mesher.build(chunks, levels, mat);
        levels[7] = 3;
        var furthest = mesher.build(chunks, levels, mat);
        assertTrue(exact.quads() > medium.quads());
        assertTrue(medium.quads() >= far.quads());
        assertTrue(far.quads() >= furthest.quads());
        assertArrayEquals(cells, chunks[7].unpack());
        // Medium LOD water Y is still exact: water's native top is 64
        // minus the retained one-ninth-block surface inset.
        boolean waterSurface = false;
        for (int i = 4; i < medium.vertices().limit(); i += ExtendedVoxelMesher.VERTEX_BYTES)
            if (Math.abs(medium.vertices().getFloat(i) - (64f - 1f / 9f)) < .001f)
                waterSurface = true;
        assertTrue(waterSurface, "Distant water Y must never be snapped to slab height");
        // Near: all top and bottom faces retain their original real Y.
        boolean exactTop = false;
        for (int i = 4; i < exact.vertices().limit(); i += ExtendedVoxelMesher.VERTEX_BYTES)
            if (Math.abs(exact.vertices().getFloat(i) - 61f) < 0.001f) exactTop = true;
        assertTrue(exactTop);
    }

    @Test void eightBlockMipKeepsAnOccupiedChildOffTheSamplingGrid() {
        short[] cells = new short[16 * 16 * 16];
        cells[11 * 256 + 7 * 16 + 7] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, -64, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        int[] levels = new int[36];
        java.util.Arrays.fill(levels, 3);
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = new ExtendedVolumeMesh().build(chunks, levels, (name, face) -> white);
        assertEquals(6, mesh.quads(),
                "Occupied voxel not aligned to 8x8 sample grid must remain visible");
        assertArrayEquals(cells, chunks[7].unpack());
    }

    @Test void fourBlockMipKeepsAnOccupiedChildOffTheSamplingGrid() {
        short[] cells = new short[4096];
        cells[7 * 256 + 7 * 16 + 7] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        int[] levels = new int[36];
        java.util.Arrays.fill(levels, 2);
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = new ExtendedVolumeMesh().build(chunks, levels, (name, face) -> white);
        assertNotNull(mesh);
        assertEquals(6, mesh.quads());
    }
}
