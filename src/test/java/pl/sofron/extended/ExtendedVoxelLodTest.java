package pl.sofron.extended;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
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

    @Test void materialPaletteKeepsLogOrientationRatherThanJustBlockId() {
        var oakX = Blocks.OAK_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var oakZ = Blocks.OAK_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        var x = ExtendedVoxelStore.serializeState(oakX);
        var z = ExtendedVoxelStore.serializeState(oakZ);
        assertTrue(x.startsWith("minecraft:oak_log["));
        assertTrue(x.contains("axis=x"));
        assertTrue(z.contains("axis=z"));
        assertNotEquals(x, z, "Real block-state variants must not collapse");
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
