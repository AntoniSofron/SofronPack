package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedWorldMeshBuilderTest {
    @Test void distantSurfaceHasFourWorldSpaceQuadsAndExcludesNearChunks() {
        short[] h = new short[9];
        Arrays.fill(h, (short) 73);
        int[] c = {0x55AA11, 0x22BB66, 0x6688CC, 0xCC8844};
        var surface = new ExtendedWorldStorage.SurfaceChunk(0, 0, h, c);
        var visible = ExtendedWorldMeshBuilder.build(0, 0, 20, 20, 4, 64,
                (x, z) -> x == 0 && z == 0 ? surface : null);
        assertEquals(4, visible.quadCount());
        assertEquals(4 * 4 * 16, visible.vertices().remaining());
        assertEquals(0.0f, visible.vertices().order(ByteOrder.nativeOrder()).getFloat(0));
        assertEquals(72.98f, visible.vertices().getFloat(4), 0.0001f);
        var near = ExtendedWorldMeshBuilder.build(0, 0, 0, 0, 4, 64,
                (x, z) -> x == 0 && z == 0 ? surface : null);
        assertEquals(0, near.quadCount());
        var tooFar = ExtendedWorldMeshBuilder.build(0, 0, 100, 100, 4, 64,
                (x, z) -> x == 0 && z == 0 ? surface : null);
        assertEquals(0, tooFar.quadCount());
    }

    @Test void chunkSnapshotsCopySurfaceArrays() {
        short[] h = new short[9];
        int[] c = new int[4];
        var s = new ExtendedWorldStorage.SurfaceChunk(-4, 7, h, c);
        h[0] = 123;
        c[0] = 0xabcdef;
        assertEquals(0, s.heights()[0]);
        assertEquals(0, s.colors()[0]);
    }
}
