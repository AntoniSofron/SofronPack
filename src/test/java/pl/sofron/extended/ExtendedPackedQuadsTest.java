package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedPackedQuadsTest {
    private static ByteBuffer quad(float y, int a, boolean repeat) {
        ByteBuffer data = ByteBuffer.allocateDirect(4 * ExtendedVoxelMesher.VERTEX_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int v = 0; v < 4; v++) {
            data.putFloat(v).putFloat(y + v / 16f).putFloat(2 + v)
                    .putFloat(repeat ? Float.intBitsToFloat(0x00800000 + (32767 << 15))
                                     : v / 4f)
                    .putFloat(repeat ? Float.intBitsToFloat(0x00800000 + (123 << 15) + 321)
                                     : v / 3f)
                    .put((byte) 12).put((byte) 34).put((byte) 56).put((byte) a);
        }
        return data.flip();
    }

    @Test void exactHeightsAndTintsArePreservedAtHalfTheGpuVertexBytes() {
        for (int alpha : new int[]{128, 143, 209, 254, 255}) {
            ByteBuffer packed = ExtendedPackedQuads.pack(quad(-63.5f, alpha, alpha > 143 && alpha < 255));
            assertEquals(48, packed.remaining());
            assertEquals(0, packed.getShort(0));
            assertEquals(-2032, packed.getShort(2));
            assertEquals(-2030, packed.getShort(8));
            assertEquals(12 | 34 << 8 | 56 << 16 | alpha << 24, packed.getInt(40));
            assertEquals(alpha > 143 && alpha < 255 ? 1 : 0, packed.getInt(44));
        }
    }

    @Test void modelAtlasUvsAreRetainedAtSubpixelResolution() {
        ByteBuffer packed = ExtendedPackedQuads.pack(quad(64, 130, false));
        assertEquals(0, packed.getShort(24) & 0xffff);
        assertEquals(0, packed.getShort(26) & 0xffff);
        assertEquals(16384, packed.getShort(28) & 0xffff, 1);
        assertEquals(21845, packed.getShort(30) & 0xffff);
        assertEquals(49151, packed.getShort(36) & 0xffff, 1);
    }

    @Test void repeatingAtlasUvBitsAreBitForBitIdentical() {
        ByteBuffer original = quad(63, 254, true);
        ByteBuffer packed = ExtendedPackedQuads.pack(original);
        assertEquals(original.getInt(12), packed.getInt(24));
        assertEquals(original.getInt(16), packed.getInt(28));
        assertEquals(0, packed.getLong(32));
    }

    @Test void preservesOriginalSourceAndMultipleQuads() {
        var source = quad(70, 128, false);
        var combined = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());
        combined.put(source.duplicate()).put(source.duplicate()).flip();
        var packed = ExtendedPackedQuads.pack(combined);
        assertEquals(96, packed.remaining());
        assertEquals(0, combined.position());
        assertEquals(192, combined.limit());
    }

    @Test void rejectedOutOfRangeAndTruncatedGeometry() {
        assertThrows(IllegalArgumentException.class, () -> ExtendedPackedQuads.pack(
                ByteBuffer.allocate(100)));
        assertThrows(IllegalArgumentException.class, () -> ExtendedPackedQuads.pack(
                quad(2000, 128, false)));
    }
}
