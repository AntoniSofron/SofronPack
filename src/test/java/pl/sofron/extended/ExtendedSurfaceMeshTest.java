package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedSurfaceMeshTest {
    @Test void serializedWaterloggedPropertiesKeepTheirBlockTint() {
        assertEquals(2, ExtendedSurfaceMesh.color(1, 2, 3, "minecraft:oak_leaves[waterlogged=false]", -1));
        assertEquals(0x619961, ExtendedSurfaceMesh.color(1, 2, 3, "minecraft:spruce_leaves[waterlogged=true]", -1));
        assertEquals(3, ExtendedSurfaceMesh.color(1, 2, 3, "minecraft:water[level=0]", -1));
    }

    @Test void aCutoutSurfaceKeepsTheSolidGroundUnderneath() {
        short[] cells = new short[4096];
        cells[5 * 16 + 5] = 1;
        cells[256 + 5 * 16 + 5] = 2;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:dirt", "minecraft:short_grass"}, cells);
        boolean[] dirtTop = {false};
        var mesh = new ExtendedSurfaceMesh().build(0, chunks, (name, face) -> {
            return new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, name.endsWith("grass"));
        });
        for (int i = 0; i < mesh.vertices().remaining(); i += 4 * ExtendedVoxelMesher.VERTEX_BYTES) {
            boolean ground = true;
            for (int vertex = 0; vertex < 4; vertex++)
                ground &= mesh.vertices().getFloat(i + vertex * ExtendedVoxelMesher.VERTEX_BYTES + 4) == 1f;
            if (ground) dirtTop[0] = true;
        }
        assertTrue(dirtTop[0]);
    }

    @Test void stoneCliffRemainsVisibleBehindTallerNeighboringLeaves() {
        short[] cells = new short[4096];
        for (int y = 0; y < 6; y++) cells[y * 256 + 17] = 1;
        cells[18] = 1;
        cells[8 * 256 + 18] = 2;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone", "minecraft:oak_leaves"}, cells);
        var mesh = new ExtendedSurfaceMesh().build(0, chunks, (name, face) ->
                new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, name.endsWith("leaves")));
        var vertices = mesh.vertices();
        boolean found = false;
        for (int i = 0; i < vertices.limit(); i += 4 * ExtendedVoxelMesher.VERTEX_BYTES) {
            boolean onCliff = true;
            float bottom = 100, top = -100;
            for (int j = 0; j < 4; j++) {
                int at = i + j * ExtendedVoxelMesher.VERTEX_BYTES;
                onCliff &= vertices.getFloat(at) == 2f;
                bottom = Math.min(bottom, vertices.getFloat(at + 4));
                top = Math.max(top, vertices.getFloat(at + 4));
            }
            found |= onCliff && bottom == 1f && top == 6f;
        }
        assertTrue(found);
    }

    @Test void levelWaterMeetsAtTheNativeHeightAcrossAChunkEdge() {
        short[] cells = new short[4096];
        java.util.Arrays.fill(cells, 0, 256, (short)1);
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        for (int x = 0; x < 2; x++)
            chunks[7 + x] = ExtendedVoxelStore.encode(x, 0, 62, 16, 0, 0, 0,
                    new String[]{"minecraft:air", "minecraft:water[level=0]"}, cells);
        var uv = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, -1, true);
        var surface = new ExtendedSurfaceMesh().build(0, chunks, (name, face) -> uv);
        var volumes = new ExtendedVolumeMesh();
        for (int level = 0; level < 3; level++) {
            int[] levels = new int[36];
            levels[7] = level;
            levels[8] = level + 1;
            var volume = volumes.build(chunks, levels, (name, face) -> uv);
            for (var mesh : new ExtendedVoxelMesher.Mesh[]{surface, volume}) {
                boolean left = false, right = false;
                for (int i = 0; i < mesh.vertices().limit(); i += 4 * ExtendedVoxelMesher.VERTEX_BYTES) {
                    float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                    boolean surfaceQuad = true;
                    for (int j = 0; j < 4; j++) {
                        int at = i + j * ExtendedVoxelMesher.VERTEX_BYTES;
                        float x = mesh.vertices().getFloat(at);
                        min = Math.min(min, x); max = Math.max(max, x);
                        surfaceQuad &= Math.abs(mesh.vertices().getFloat(at + 4) - (63f - 1f/9f)) < .0001f;
                    }
                    left |= surfaceQuad && min == 0 && max == 16;
                    right |= surfaceQuad && min == 16 && max == 32;
                }
                assertTrue(left && right);
            }
        }
    }

    @Test void topOffSamplingGridSurvivesAtEveryResolution() {
        short[] cells = new short[16 * 16 * 16];
        cells[15 * 256 + 7 * 16 + 11] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        var builder = new ExtendedSurfaceMesh();
        var white = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        for (int detail : new int[]{1, 2, 4, 8}) {
            var mesh = builder.build(Integer.numberOfTrailingZeros(detail), chunks, (name, face) -> white);
            assertTrue(mesh.quads() > 0);
            var vertices = mesh.vertices();
            float highest = Float.NEGATIVE_INFINITY;
            for (int i = 4; i < vertices.limit(); i += ExtendedVoxelMesher.VERTEX_BYTES)
                highest = Math.max(highest, vertices.getFloat(i));
            assertEquals(16f, highest);
        }
    }
    @Test void farEightByEightPreviewKeepsSeaLevelAndOffGridSummits() {
        short[] cells = new short[16 * 16 * 16];
        cells[15 * 256 + 7 * 16 + 11] = 1;
        var chunks = new ExtendedVoxelStore.VoxelChunk[36];
        chunks[7] = ExtendedVoxelStore.encode(0, 0, 0, 16, 0, 0, 0,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        var material = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        // Source chunk at the first 4x4 tile slot, ring=3 encoded in 2 bits.
        var mesh = new ExtendedSurfaceMesh().build(3L, chunks, (name, face) -> material);
        assertTrue(mesh.quads() > 0);
        float maximum = Float.NEGATIVE_INFINITY;
        for (int i = 4; i < mesh.vertices().limit(); i += ExtendedVoxelMesher.VERTEX_BYTES)
            maximum = Math.max(maximum, mesh.vertices().getFloat(i));
        assertEquals(16f, maximum, "8x8 previews must preserve the highest occupied child");
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
