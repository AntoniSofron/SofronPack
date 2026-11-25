package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedVoxelCodecTest {
    private static byte[] legacy(short[] cells) {
        byte[] raw = new byte[cells.length * 2];
        for (int i = 0; i < cells.length; i++) {
            raw[2*i] = (byte)cells[i];
            raw[2*i+1] = (byte)(cells[i] >>> 8);
        }
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(raw); d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!d.finished()) out.write(buffer, 0, d.deflate(buffer));
            return out.toByteArray();
        } finally { d.end(); }
    }

    @Test void allBlocksPreservedForNaturalAndHighEntropyTerrain() {
        Random random = new Random(42);
        for (int palette : new int[]{1, 2, 8, 31, 256, 32767}) {
            short[] cells = new short[256 * 384];
            for (int i = 0; i < cells.length; i++) cells[i] = (short)random.nextInt(palette);
            byte[] encoded = ExtendedVoxelCodec.encode(cells, palette);
            assertArrayEquals(cells, ExtendedVoxelCodec.decode(encoded, cells.length, palette));
            assertTrue(encoded.length <= legacy(cells).length);
        }
        short[] terrain = new short[256 * 384];
        for (int c = 0; c < 256; c++) {
            int top = 140 + c % 13;
            for (int y = 0; y < top; y++) terrain[y * 256 + c] = (short)(y < top - 4 ? 1 : 2);
        }
        byte[] encoded = ExtendedVoxelCodec.encode(terrain, 3);
        assertArrayEquals(terrain, ExtendedVoxelCodec.decode(encoded, terrain.length, 3));
        assertTrue(encoded.length < legacy(terrain).length);
    }

    @Test void legacyZlibRemainsReadable() {
        short[] cells = new short[256 * 20];
        Arrays.fill(cells, (short)1);
        cells[200] = 2;
        assertArrayEquals(cells, ExtendedVoxelCodec.decode(legacy(cells), cells.length, 3));
    }

    @Test void truncatedDataIsRejected() {
        short[] cells = new short[256 * 384];
        byte[] encoded = ExtendedVoxelCodec.encode(cells, 1);
        assertThrows(IllegalArgumentException.class,
                () -> ExtendedVoxelCodec.decode(Arrays.copyOf(encoded, encoded.length - 2), cells.length, 1));
    }
}
