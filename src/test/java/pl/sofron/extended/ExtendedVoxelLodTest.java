package pl.sofron.extended;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedVoxelLodTest {
    @Test void compressedVoxelPaletteRoundTripsWithoutChangingMaterials() throws Exception {
        short[] blocks = new short[ExtendedVoxelStore.GRID * ExtendedVoxelStore.GRID * 20];
        Arrays.fill(blocks, (short) 1);
        blocks[5] = 2;
        blocks[71] = 0;
        var original = ExtendedVoxelStore.encode(-40, 12, -64, 20,
                0x31a444, 0x406a39, 0x256db1,
                new String[] {"minecraft:air", "minecraft:stone", "minecraft:oak_leaves"}, blocks);
        byte[] record = ExtendedVoxelStore.payload(original);
        var restored = ExtendedVoxelStore.readPayload(record);
        assertEquals(-40, restored.chunkX());
        assertEquals(12, restored.chunkZ());
        assertEquals(-64, restored.minY());
        assertArrayEquals(blocks, restored.unpack());
        assertArrayEquals(original.palette(), restored.palette());
        assertEquals(0x31a444, restored.grassTint());
        assertEquals(0x406a39, restored.foliageTint());
        assertEquals(0x256db1, restored.waterTint());
    }

    @Test void blockStatePaletteKeepsDistinctLogOrientationsAfterReload() throws Exception {
        short[] cells = new short[ExtendedVoxelStore.GRID * ExtendedVoxelStore.GRID];
        cells[0] = 1;
        cells[1] = 2;
        var original = ExtendedVoxelStore.encode(0, 0, 0, 1,
                new String[] {"minecraft:air", "minecraft:oak_log[axis=x]",
                        "minecraft:oak_log[axis=z]"}, cells);
        var copy = ExtendedVoxelStore.readPayload(ExtendedVoxelStore.payload(original));
        assertNotEquals(copy.palette()[1], copy.palette()[2]);
        assertEquals("minecraft:oak_log[axis=x]", copy.palette()[1]);
        assertEquals("minecraft:oak_log[axis=z]", copy.palette()[2]);
        assertArrayEquals(cells, copy.unpack());
    }

    @Test void independentStoneVoxelHasSixSquareTexturedFaces() {
        short[] cells = new short[ExtendedVoxelStore.GRID * ExtendedVoxelStore.GRID * 4];
        cells[(2 * ExtendedVoxelStore.GRID + 3) * ExtendedVoxelStore.GRID + 4] = 1;
        var chunk = ExtendedVoxelStore.encode(0, 0, 0, 4,
                new String[] {"minecraft:air", "minecraft:stone"}, cells);
        var material = new ExtendedVoxelMesher.MaterialUV(
                0.1f, 0.2f, 0.125f, 0.225f, 0xffffff, false);
        var mesh = ExtendedVoxelMesher.build(0, 0, 30, 30, 12, 64, 1,
                (x, z) -> x == 0 && z == 0 ? chunk : null,
                (id, direction) -> material);
        assertEquals(6, mesh.quads());
        assertEquals(6 * 4 * 24, mesh.vertices().remaining());
    }

    @Test void undergroundNeighboursAreCulledWithoutHidingTopFace() {
        short[] cells = new short[ExtendedVoxelStore.GRID * ExtendedVoxelStore.GRID * 4];
        cells[0] = 1;
        cells[1] = 1; // two adjacent blocks share a face; it must not be drawn
        var chunk = ExtendedVoxelStore.encode(0, 0, 0, 4,
                new String[] {"minecraft:air", "minecraft:dirt"}, cells);
        var material = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = ExtendedVoxelMesher.build(0, 0, 20, 20, 12, 64, 1,
                (x,z) -> x == 0 && z == 0 ? chunk : null, (id, dir) -> material);
        assertEquals(10, mesh.quads());
    }

    @Test void sharedVoxelFaceBetweenNeighbouringTilesIsNotRendered() {
        int grid = ExtendedVoxelStore.GRID;
        short[] left = new short[grid * grid];
        short[] right = new short[grid * grid];
        left[grid - 1] = 1; // easternmost block in chunk (3,0)
        right[0] = 1; // matching westernmost block of chunk (4,0)
        var west = ExtendedVoxelStore.encode(3, 0, 0, 1,
                new String[] {"minecraft:air", "minecraft:stone"}, left);
        var east = ExtendedVoxelStore.encode(4, 0, 0, 1,
                new String[] {"minecraft:air", "minecraft:stone"}, right);
        var texture = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var mesh = ExtendedVoxelMesher.build(0, 0, 20, 20, 12, 64, 1,
                (cx, cz) -> cx == 3 && cz == 0 ? west
                        : cx == 4 && cz == 0 ? east : null,
                (block, face) -> texture);
        assertEquals(5, mesh.quads(), "Face shared with a neighbouring LOD tile must be culled");
    }

    @Test void aMissingNeighbourDoesNotCreateAFalseStoneWall() {
        int grid = ExtendedVoxelStore.GRID;
        short[] cells = new short[grid * grid * 16];
        // A coarse, four-block-wide column at the eastern boundary.
        // The missing neighbour must not become a 16-block stone curtain.
        for (int y = 0; y < 16; y++) {
            for (int x = 12; x < 16; x++) {
                cells[(y * grid + 8) * grid + x] = 1;
            }
        }
        var voxel = ExtendedVoxelStore.encode(3, 0, 0, 16,
                new String[]{"minecraft:air", "minecraft:stone"}, cells);
        int[] eastFaces = {0};
        var material = new ExtendedVoxelMesher.MaterialUV(0, 0, 1, 1, 0xffffff, false);
        var result = ExtendedVoxelMesher.build(0, 0, 20, 20, 12, 64, 4,
                (x, z) -> x == 3 && z == 0 ? voxel : null,
                (name, face) -> {
                    if (face == Direction.EAST) eastFaces[0]++;
                    return material;
                });
        assertTrue(result.quads() > 0);
        assertEquals(0, eastFaces[0], "Unloaded east neighbour must not become a vertical wall");
    }

    @Test void normalRenderRangeKeepsPriority() {
        short[] cells = new short[ExtendedVoxelStore.GRID * ExtendedVoxelStore.GRID];
        Arrays.fill(cells, (short)1);
        var chunk = ExtendedVoxelStore.encode(0,0,0,1,
                new String[]{"minecraft:air","minecraft:dirt"}, cells);
        var m = new ExtendedVoxelMesher.MaterialUV(0,0,1,1,0xffffff,false);
        var mesh = ExtendedVoxelMesher.build(0,0,0,0,12,64,1,
                (x,z) -> x==0&&z==0 ? chunk : null,(id,dir)->m);
        assertEquals(0,mesh.quads());
    }
}
