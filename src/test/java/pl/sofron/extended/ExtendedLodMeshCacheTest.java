package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodMeshCacheTest {
    @TempDir Path saveFolder;

    private static ExtendedLodMeshCache.Packed tile(int quads) {
        ByteBuffer vertices = ByteBuffer.allocateDirect(quads * ExtendedPackedQuads.QUAD_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < vertices.capacity(); i++) vertices.put((byte) (i * 7));
        vertices.flip();
        return new ExtendedLodMeshCache.Packed(vertices, quads);
    }

    @Test void persistsAllLevelVariantsAndRestoresExactGpuBytesAcrossInstances() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 5);
        long[] details = {0L, 0x55555555L, 0xaaaaaaaaL, 0xffffffffL};
        for (int i = 0; i < 4; i++) {
            int tx = -7, tz = 18, quads = 100 - i * 20;
            ExtendedLodMeshCache.save(saveFolder, tx, tz, details[i], hash, tile(quads));
            assertTrue(ExtendedLodMeshCache.hasSaved(saveFolder, tx, tz, details[i]));
            var restored = ExtendedLodMeshCache.load(saveFolder, tx, tz, details[i], hash);
            assertNotNull(restored);
            assertEquals(quads, restored.quads());
            assertEquals(tile(quads).vertices(), restored.vertices());
        }
        assertNull(ExtendedLodMeshCache.load(saveFolder, -7, 18, 0x00001000L, hash));
    }

    @Test void invalidatesOnWorldOrTextureFingerprintChange() {
        byte[] hash = new byte[32];
        ExtendedLodMeshCache.save(saveFolder, 2, 3, 0L, hash, tile(4));
        hash[6] = 10;
        assertNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
        hash[6] = 0;
        assertNotNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
    }

    @Test void truncatedOrCorruptedFileDoesNotCrashOrRenderGarbage() throws Exception {
        byte[] hash = new byte[32];
        ExtendedLodMeshCache.save(saveFolder, 2, 3, 0L, hash, tile(5));
        Path file;
        try (var paths = Files.walk(saveFolder)) {
            file = paths.filter(p -> p.toString().endsWith(".slm")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 3] ^= 0x55;
        Files.write(file, bytes);
        assertNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
        Files.write(file, Arrays.copyOf(bytes, 20));
        assertNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
    }
}
