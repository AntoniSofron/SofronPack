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

    @Test void largeMeshesRoundTripAcrossStreamingCompressionBufferBoundaries() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0x36);
        var original = tile(12_000); // 576 KiB of native 48-byte quad records
        ExtendedLodMeshCache.saveDeferred(saveFolder, -12, 9, 0x11aabbccL, hash, original);
        ExtendedLodMeshCache.awaitPendingSaves();
        var saved = ExtendedLodMeshCache.load(saveFolder, -12, 9, 0x11aabbccL, hash);
        assertNotNull(saved, "Background save must complete before shutdown barrier");
        assertEquals(original.quads(), saved.quads());
        assertEquals(original.vertices(), saved.vertices());
    }

    @Test void savedFileIsVisibleToTheInMemoryIndexWithoutFilesystemPolling() {
        byte[] hash = new byte[32];
        ExtendedLodMeshCache.save(saveFolder, -11, 17, 0x11aabbccL, hash, tile(2));
        assertTrue(ExtendedLodMeshCache.hasSaved(saveFolder, -11, 17, 0x11aabbccL));
        assertFalse(ExtendedLodMeshCache.hasSaved(saveFolder, -11, 17, 0x11223344L));
        assertTrue(ExtendedLodMeshCache.exists(saveFolder));
    }

    @Test void invalidatesOnWorldOrTextureFingerprintChange() {
        byte[] hash = new byte[32];
        ExtendedLodMeshCache.save(saveFolder, 2, 3, 0L, hash, tile(4));
        hash[6] = 10;
        assertNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
        hash[6] = 0;
        assertNotNull(ExtendedLodMeshCache.load(saveFolder, 2, 3, 0L, hash));
    }

    @Test void sourceEditsOrAtlasUvChangesInvalidateTheSameSavedTile() {
        var sources = new ExtendedVoxelStore.VoxelChunk[36];
        short[] raw = new short[16 * 16 * 4];
        raw[0] = 1;
        sources[7] = ExtendedVoxelStore.encode(0, 0, 0, 4,
                new String[]{"minecraft:air", "minecraft:stone"}, raw);
        ExtendedVoxelMesher.MaterialResolver oldAtlas = (name, direction) ->
                new ExtendedVoxelMesher.MaterialUV(0f, 0f, .5f, .5f, 0xffffff, false);
        ExtendedVoxelMesher.MaterialResolver changedAtlas = (name, direction) ->
                new ExtendedVoxelMesher.MaterialUV(.1f, .1f, .7f, .7f, 0xffffff, false);
        byte[] original = ExtendedLodMeshFingerprint.of(sources, oldAtlas);
        byte[] same = ExtendedLodMeshFingerprint.of(sources, oldAtlas);
        assertArrayEquals(original, same, "Cache signatures must be stable between launches");
        assertFalse(Arrays.equals(original, ExtendedLodMeshFingerprint.of(sources, changedAtlas)),
                "A changed resource-pack atlas must invalidate saved GPU quads");
        raw[0] = 0;
        sources[7] = ExtendedVoxelStore.encode(0, 0, 0, 4,
                new String[]{"minecraft:air", "minecraft:stone"}, raw);
        assertFalse(Arrays.equals(original, ExtendedLodMeshFingerprint.of(sources, oldAtlas)),
                "Changing even one block must invalidate the stored render mesh");
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
