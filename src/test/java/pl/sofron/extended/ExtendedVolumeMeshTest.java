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
