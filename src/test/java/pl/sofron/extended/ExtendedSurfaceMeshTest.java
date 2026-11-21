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
            var mesh = builder.build(Integer.numberOfTrailingZeros(detail), chunks, (name, face) -> white);
            assertTrue(mesh.quads() > 0);
            var vertices = mesh.vertices();
            float highest = Float.NEGATIVE_INFINITY;
            for (int i = 4; i < vertices.limit(); i += ExtendedVoxelMesher.VERTEX_BYTES)
                highest = Math.max(highest, vertices.getFloat(i));
            assertEquals(16f, highest);
        }
    }
    @Test void integratedScanMesherReducesAFlatChunkToOneTexturedTop() {
        short[] cells = new short[16 * 16 * 16];
        java.util.Arrays.fill(cells, 0, 256, (short)1);
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = new ExtendedSurfaceMesh().build(0, chunks, (name, face) -> white);
        assertEquals(1, mesh.quads());
        assertEquals(16f, mesh.vertices().getFloat(2 * ExtendedVoxelMesher.VERTEX_BYTES));
    }
}
