package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedSurfaceMeshTest {
    @Test void topOffSamplingGridSurvivesAtEveryResolution() {
        short[] cells = new short[16 * 16 * 16];
        cells[15 * 256 + 7 * 16 + 11] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        var builder = new ExtendedSurfaceMesh();
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        for (int detail : new int[]{1, 2, 4}) {
            var mesh = builder.build(0, 0, detail, chunks, (name, face) -> white);
            assertTrue(mesh.quads() > 0);
            assertEquals(16f, mesh.vertices().getFloat(4));
        }
    }
}
