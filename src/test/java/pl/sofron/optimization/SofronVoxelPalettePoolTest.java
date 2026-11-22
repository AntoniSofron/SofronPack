package pl.sofron.optimization;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import pl.sofron.extended.ExtendedVoxelStore;
import pl.sofron.optimization.memory.SofronVoxelPalettePool;

import static org.junit.jupiter.api.Assertions.*;

/** Distinct snapshots share only immutable material identifiers, never arrays. */
class SofronVoxelPalettePoolTest {
    @Test void identicalNameValuesAreCanonicalizedAndArraysStayIndependent() {
        String a = new String("minecraft:spruce_log[axis=x]");
        String b = new String("minecraft:spruce_log[axis=x]");
        assertNotSame(a, b);
        assertSame(SofronVoxelPalettePool.canonicalize(a),
                SofronVoxelPalettePool.canonicalize(b));

        String[] materialsA = {"minecraft:air", a};
        String[] materialsB = {"minecraft:air", b};
        short[] raw = new short[16 * 16];
        Arrays.fill(raw, (short) 1);
        var first = ExtendedVoxelStore.encode(0, 0, 0, 1, materialsA, raw);
        var second = ExtendedVoxelStore.encode(1, 0, 0, 1, materialsB, raw);

        assertNotSame(first.palette(), second.palette());
        assertSame(first.palette()[1], second.palette()[1]);
        materialsA[1] = "minecraft:stone";
        assertEquals("minecraft:spruce_log[axis=x]", first.palette()[1]);
        first.palette()[1] = "minecraft:dirt";
        assertEquals("minecraft:spruce_log[axis=x]", second.palette()[1]);
    }

    @Test void nullNamesAreRejectedInsteadOfCachingInvalidSnapshots() {
        assertThrows(IllegalArgumentException.class, () ->
                SofronVoxelPalettePool.canonicalize(null));
    }
}
