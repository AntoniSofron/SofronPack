package pl.sofron.terralab.trees;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowLeafGradientTest {
    @Test void exactSteps() {
        assertEquals(0xFFFFFF, SnowLeafGradient.blend(0x000000, 8));
        assertEquals(0x808080, SnowLeafGradient.blend(0, 4));
        assertEquals(0x202020, SnowLeafGradient.blend(0, 1));
        assertEquals(0x123456, SnowLeafGradient.blend(0x123456, 0));
    }
    @Test void eightLeavesAndGap() {
        String[] palette = {"minecraft:air", "minecraft:oak_leaves", "minecraft:snow[layers=1]", "minecraft:cherry_leaves"};
        short[] cells = new short[12 * 256];
        for (int y = 0; y < 10; y++) cells[y * 256] = 1;
        cells[10 * 256] = 2;
        byte[] frost = SnowLeafGradient.voxelSteps(palette, cells);
        for (int y = 2; y < 10; y++) assertEquals(y - 1, frost[y * 256]);
        assertEquals(0, frost[256]);
        cells[6 * 256] = 3;
        frost = SnowLeafGradient.voxelSteps(palette, cells);
        assertEquals(0, frost[5 * 256]);
        assertFalse(SnowLeafGradient.eligible("minecraft:flowering_azalea_leaves"));
        assertFalse(SnowLeafGradient.eligible("minecraft:red_poplar_leaves"));
        assertTrue(SnowLeafGradient.eligible("sofronpack:fir_leaves[distance=7]"));
    }
}
