package pl.sofron.extended.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.extended.ExtendedLodLevels;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import pl.sofron.extended.ExtendedVoxelMesher;
import pl.sofron.extended.ExtendedVoxelStore;
import net.minecraft.resources.Identifier;
import com.mojang.renderpearl.api.textures.FilterMode;

/**
 * Native RenderPearl (OpenGL + Vulkan) renderer of compact distant surface
 * tiles. The renderer is a client component of SofronPack itself: it does not
 * discover, link to, or load Voxy.
 *
 * Only 16x16-chunk tiles receive GPU buffers. Each changed source chunk
 * invalidates its tile. The normal render distance remains under Sodium's
 * control; all meshing/uploads have a hard per-frame budget.
 */
public final class ExtendedWorldLodRenderer {
    private static final int TILE = ExtendedVoxelMesher.TILE_CHUNKS;
    private static final int FAR = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final int UPLOADS_PER_FRAME = 8;
    private static final long FRAME_MESH_BUDGET_NS = 5_000_000L;
    // A 128-chunk radius can require many more visible tiles than 64.
    // Evicting after only 384 tiles caused distant regions to vanish.
    private static final int MAX_GPU_TILES = 3_072;
    private static final ExtendedVoxelAtlas ATLAS = new ExtendedVoxelAtlas();
    private static final int MAX_QUADS = ExtendedVoxelMesher.MAX_QUADS;
    private static final Map<Long, GpuTile> MESHES = new HashMap<>();
    private static final Set<Long> DIRTY = new HashSet<>();

    private static GpuDevice device;
    private static GpuBuffer quadIndices;
    private static GpuBuffer longProjection;
    private static final ByteBuffer PROJECTION_STAGING = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static long worldGeneration = Long.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static int lastRenderDistance;
    private static boolean failed;
    private static boolean loggedFirstUpload;
    private static boolean loggedFirstDraw;

    private record GpuTile(GpuBuffer buffer, int quads) implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }

    private ExtendedWorldLodRenderer() {}

    /**
     * Called before Minecraft opens any RenderPass. GPU uploads and buffer
     * retirement must never happen inside a live Vulkan render pass.
     */
    public static void prepareFrame(double cameraX, double cameraZ) {
        Minecraft mc = Minecraft.getInstance();
        if (!ExtendedWorldConfig.get().enabled || mc.level == null
                || mc.getSingleplayerServer() == null || mc.player == null) {
            if (device != null) close();
            return;
        }
        if (failed) {
            if (device != null) close();
            return;
        }
        GpuDevice now = RenderSystem.getDevice();
        if (device != now || worldGeneration != ExtendedVoxelStore.generation()) {
            close();
            device = now;
            worldGeneration = ExtendedVoxelStore.generation();
        }
        if (ExtendedVoxelStore.count() == 0) return;
        if (ATLAS.refreshModels()) {
            clearMeshes();
            centerX = centerZ = Integer.MIN_VALUE;
        }

        int cx = ((int)Math.floor(cameraX)) >> 4;
        int cz = ((int)Math.floor(cameraZ)) >> 4;
        int distance = Math.max(2, mc.options.getEffectiveRenderDistance());
        int minTX = Math.floorDiv(cx - FAR, TILE), maxTX = Math.floorDiv(cx + FAR, TILE);
        int minTZ = Math.floorDiv(cz - FAR, TILE), maxTZ = Math.floorDiv(cz + FAR, TILE);
        if (centerX != cx || centerZ != cz || lastRenderDistance != distance) {
            boolean first = centerX == Integer.MIN_VALUE || lastRenderDistance != distance;
            centerX = cx; centerZ = cz; lastRenderDistance = distance;
            for (int tx = minTX; tx <= maxTX; tx++) for (int tz = minTZ; tz <= maxTZ; tz++) {
                long key = ExtendedVoxelStore.pack(tx, tz);
                if ((!MESHES.containsKey(key) || first ||
                        intersectsMovingRings(tx, tz, cx, cz, distance)) && hasChunkData(tx, tz)) {
                    DIRTY.add(key);
                }
            }
            // Avoid retaining coordinates from worlds/areas the player has
            // left. They were previously scanned again on every frame.
            DIRTY.removeIf(key -> {
                int tx = (int)(key >> 32), tz = (int)(long)key;
                return tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ;
            });
            Iterator<Map.Entry<Long, GpuTile>> it = MESHES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, GpuTile> entry = it.next();
                int tx = (int)(entry.getKey() >> 32), tz = (int)(long)entry.getKey();
                if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ) {
                    entry.getValue().close();
                    it.remove();
                }
            }
        }

        // Publish surface changes to the GPU gradually; do not iterate every
        // saved chunk or upload the whole LOD mesh when one surface changes.
        for (int i = 0; i < 2048; i++) {
            Long changed = ExtendedVoxelStore.pollChanged();
            if (changed == null) break;
            int x = (int)(changed >> 32), z = (int)(long)changed;
            int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
            if (tx >= minTX && tx <= maxTX && tz >= minTZ && tz <= maxTZ)
                DIRTY.add(ExtendedVoxelStore.pack(tx, tz));
        }
        // Invalidate LOD around normal client chunks that have just arrived
        // or been unloaded. Otherwise cached meshes can remain blank while the
        // camera stands still waiting for the network's chunk stream.
        var clientChunks = mc.level.getChunkSource();
        for (long packed : clientChunks.addedLoadedChunks()) {
            int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
            DIRTY.add(ExtendedVoxelStore.pack(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)));
        }
        for (long packed : clientChunks.removedLoadedChunks()) {
            int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
            DIRTY.add(ExtendedVoxelStore.pack(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)));
        }

        try {
            ensureIndices();
            uploadFarProjection(mc);
            // Pick the nearest visible dirty tile. Random HashSet iteration
            // caused important close LOD chunks to appear after far-off ones.
            var view = mc.gameRenderer.mainCamera().forwardVector();
            double px = cameraX, pz = cameraZ;
            long meshDeadline = System.nanoTime() + FRAME_MESH_BUDGET_NS;
            for (int rebuilt = 0; rebuilt < UPLOADS_PER_FRAME; rebuilt++) {
                // Spend spare frame time on small far tiles; avoid 8 heavy
                // near-resolution rebuilds stalling the render thread.
                if (rebuilt > 0 && System.nanoTime() >= meshDeadline) break;
                Long best = null;
                double nearest = Double.POSITIVE_INFINITY;
                for (long key : DIRTY) {
                    int tx = (int)(key >> 32), tz = (int)key;
                    if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ) continue;
                    if (!isInView(tx, tz, px, pz, view.x(), view.z())) continue;
                    double dx = (long)tx * TILE * 16 + TILE * 8 - px;
                    double dz = (long)tz * TILE * 16 + TILE * 8 - pz;
                    double distanceSq = dx * dx + dz * dz;
                    if (distanceSq < nearest) { nearest = distanceSq; best = key; }
                }
                if (best == null) break;
                DIRTY.remove(best);
                rebuildTile((int)(best >> 32), (int)(long)best, cx, cz, distance);
                trimGpuCache(cx, cz);
            }
        } catch (RuntimeException | LinkageError problem) {
            System.err.println("[SofronPack] Extended World GPU preparation failed: "
                    + problem.getMessage());
            close();
            failed = true;
        }
    }

    /**
     * The draw callback only binds already-created resources. No mesh
     * generation, buffer allocation or command submission occurs here.
     */
    public static void render(RenderPass pass, ChunkRenderMatrices matrices,
                              TerrainRenderPass terrainPass, CameraTransform camera) {
        if (terrainPass != DefaultTerrainRenderPasses.CUTOUT || pass == null
                || failed || MESHES.isEmpty() || quadIndices == null || longProjection == null) return;
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(ExtendedVoxelPipeline.TERRAIN));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", longProjection.slice());
            var blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(
                    Identifier.parse("minecraft:textures/atlas/blocks.png")).getTextureView();
            pass.setUniform("Sampler0", blockAtlas,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setIndexBuffer(quadIndices, IndexType.INT);
            var forward = Minecraft.getInstance().gameRenderer.mainCamera().forwardVector();
            for (Map.Entry<Long, GpuTile> entry : MESHES.entrySet()) {
                long key = entry.getKey();
                int tx = (int)(key >> 32), tz = (int)(long)key;
                if (!isInView(tx, tz, camera.x, camera.z, forward.x(), forward.z())) continue;
                GpuTile tile = entry.getValue();
                if (tile.quads == 0) continue;
                Matrix4f relative = new Matrix4f(matrices.modelView()).translate(
                        (float)((long)tx * TILE * 16 - camera.x),
                        (float)-camera.y,
                        (float)((long)tz * TILE * 16 - camera.z));
                var transform = RenderSystem.getDynamicUniforms().writeTransform(
                        relative, new Vector4f(1f, 1f, 1f, 1f),
                        new Vector3f(
                                (float)((long)tx * TILE * 16 - camera.x),
                                (float)-camera.y,
                                (float)((long)tz * TILE * 16 - camera.z)),
                        new Matrix4f());
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, tile.buffer.slice());
                pass.drawIndexed(tile.quads * 6, 1, 0, 0, 0);
                if (!loggedFirstDraw) {
                    loggedFirstDraw = true;
                    System.out.println("[SofronPack] Textured Extended World GPU draw issued: "
                            + tile.quads + " square block faces");
                }
            }
        } catch (RuntimeException | LinkageError problem) {
            // Defer freeing GPU buffers until the beginning of the next frame.
            failed = true;
            System.err.println("[SofronPack] Extended World draw disabled: "
                    + problem.getMessage());
        }
    }

    /**
     * Vanilla projection clips terrain at the ordinary view radius. A separate
     * 128-chunk LOD projection extends that far plane without altering near
     * terrain, camera FOV, chunk simulation or game options.
     */
    private static void uploadFarProjection(Minecraft mc) {
        Matrix4f projection = new Matrix4f(mc.gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.projectionMatrix);
        float near = 0.05f, far = FAR * 16f + 512f;
        boolean reversed = DepthStencilState.DEFAULT.depthTest()
                .equals(CompareOp.GREATER_THAN_OR_EQUAL);
        if (reversed) {
            float tmp = near; near = far; far = tmp;
        }
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        projection.m22((zeroToOne ? far : far + near) / (near - far));
        projection.m32((zeroToOne ? far : far + far) * near / (near - far));
        PROJECTION_STAGING.clear();
        projection.get(0, PROJECTION_STAGING);
        PROJECTION_STAGING.position(0);
        PROJECTION_STAGING.limit(64);
        if (longProjection == null) {
            longProjection = device.createBuffer(() -> "SofronPack LOD projection",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        }
        var encoder = device.createCommandEncoder();
        encoder.writeToBuffer(longProjection.slice(), PROJECTION_STAGING);
        encoder.submit();
    }

    private static boolean intersectsMovingRings(int tx, int tz, int cx, int cz, int near) {
        int x0 = tx * TILE, x1 = x0 + TILE - 1;
        int z0 = tz * TILE, z1 = z0 + TILE - 1;
        int minimum = Math.max(
                Math.max(0, Math.max(x0 - cx, cx - x1)),
                Math.max(0, Math.max(z0 - cz, cz - z1)));
        int maximum = Math.max(
                Math.max(Math.abs(x0 - cx), Math.abs(x1 - cx)),
                Math.max(Math.abs(z0 - cz), Math.abs(z1 - cz)));
        return (minimum <= near + 2 && maximum >= near - 2)
                || (minimum <= FAR + 1 && maximum >= FAR - 1);
    }

    private static void rebuildTile(int tx, int tz, int cx, int cz, int near) {
        int dx = Math.abs(tx * TILE + TILE / 2 - cx);
        int dz = Math.abs(tz * TILE + TILE / 2 - cz);
        int range = Math.max(dx, dz);
        int detail = ExtendedLodLevels.voxelSize(range);
        var client = Minecraft.getInstance().level;
        var mesh = ExtendedVoxelMesher.build(tx, tz, cx, cz, near, FAR, detail,
                ExtendedVoxelStore::get, ATLAS,
                (chunkX, chunkZ) -> client != null &&
                        client.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null);
        GpuTile previous = MESHES.remove(ExtendedVoxelStore.pack(tx, tz));
        if (previous != null) previous.close();
        if (mesh.quads() == 0) return;
        GpuBuffer buffer = device.createBuffer(() -> "SofronPack Extended World tile",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertices());
        MESHES.put(ExtendedVoxelStore.pack(tx, tz), new GpuTile(buffer, mesh.quads()));
        if (!loggedFirstUpload) {
            loggedFirstUpload = true;
            System.out.println("[SofronPack] Textured Extended World LOD GPU tile uploaded: "
                    + mesh.quads() + " square block faces");
        }
    }

    private static void ensureIndices() {
        if (quadIndices != null) return;
        ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_QUADS * 6 * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int q = 0; q < MAX_QUADS; q++) {
            int v = q * 4;
            buffer.putInt(v).putInt(v + 1).putInt(v + 2)
                    .putInt(v + 2).putInt(v + 3).putInt(v);
        }
        buffer.flip();
        quadIndices = device.createBuffer(() -> "SofronPack distant-world quad indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, buffer);
    }

    private static boolean hasChunkData(int tx, int tz) {
        for (int dz = 0; dz < TILE; dz++) {
            for (int dx = 0; dx < TILE; dx++) {
                if (ExtendedVoxelStore.has(tx * TILE + dx, tz * TILE + dz)) return true;
            }
        }
        return false;
    }

    private static boolean isInView(int tx, int tz, double px, double pz,
                                    float lookX, float lookZ) {
        double dx = (long) tx * TILE * 16 + TILE * 8 - px;
        double dz = (long) tz * TILE * 16 + TILE * 8 - pz;
        double length = Math.sqrt(dx * dx + dz * dz);
        // Keep tiles behind the player at very close range and a generous
        // horizontal field of view to avoid corner pop-in on ultrawide screens.
        return length < 128 || (dx * lookX + dz * lookZ) >= -length * .2;
    }

    private static void trimGpuCache(int cx, int cz) {
        if (MESHES.size() <= MAX_GPU_TILES) return;
        Long worst = null;
        long worstDistance = -1;
        for (long key : MESHES.keySet()) {
            long tx = (int)(key >> 32), tz = (int)(long)key;
            long dx = tx * TILE + TILE / 2 - cx;
            long dz = tz * TILE + TILE / 2 - cz;
            long distance = dx * dx + dz * dz;
            if (distance > worstDistance) { worstDistance = distance; worst = key; }
        }
        if (worst != null) {
            GpuTile evicted = MESHES.remove(worst);
            if (evicted != null) {
                evicted.close();
                // Do not permanently lose already generated chunks when the
                // GPU cache reaches its bound. Schedule a fresh mesh if the
                // camera later turns back toward this tile.
                DIRTY.add(worst);
            }
        }
    }

    private static void clearMeshes() {
        for (GpuTile tile : MESHES.values()) tile.close();
        MESHES.clear();
        DIRTY.clear();
    }

    /** Release all device resources from the render thread. */
    public static void close() {
        clearMeshes();
        ATLAS.clear();
        ExtendedVoxelMesher.clearCachedChunks();
        if (quadIndices != null) quadIndices.close();
        if (longProjection != null) longProjection.close();
        quadIndices = null;
        longProjection = null;
        device = null;
        worldGeneration = Long.MIN_VALUE;
        centerX = centerZ = Integer.MIN_VALUE;
        lastRenderDistance = 0;
        loggedFirstUpload = loggedFirstDraw = false;
    }
}
