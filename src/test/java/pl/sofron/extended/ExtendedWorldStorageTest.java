package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedWorldStorageTest {
    @Test void binaryRecordRoundTripsAndSupportsNegativeCoordinates() throws Exception {
        var input = new ExtendedWorldStorage.SurfaceChunk(
                -142, 203, new short[] {-60,-30,0,20,64,127,256,290,315},
                new int[] {0x123456,0x007766,0xabcdef,0x990044});
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        ExtendedWorldStorage.writeChunk(out, input);
        assertEquals(42, bytes.size());
        var copy = ExtendedWorldStorage.readChunk(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(input.chunkX(), copy.chunkX());
        assertEquals(input.chunkZ(), copy.chunkZ());
        assertArrayEquals(input.heights(), copy.heights());
        assertArrayEquals(input.colors(), copy.colors());
        assertTrue(input.sameSurface(copy));
    }
}
