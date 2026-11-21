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
