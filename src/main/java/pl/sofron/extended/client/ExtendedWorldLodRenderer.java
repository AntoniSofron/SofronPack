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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Comparator;
import org.joml.FrustumIntersection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pl.sofron.extended.ExtendedSurfaceMesh;
import pl.sofron.extended.ExtendedVolumeMesh;
import pl.sofron.extended.ExtendedTerrainPreview;
import java.util.Set;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.extended.ExtendedLodVertexSpace;
import pl.sofron.extended.ExtendedLodLevels;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import pl.sofron.extended.ExtendedVoxelMesher;
import pl.sofron.extended.ExtendedVoxelStore;
import com.mojang.renderpearl.api.textures.GpuSampler;
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
    private static final int UPLOADS_PER_FRAME = 2;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "SofronPack-LOD-Mesh");
        thread.setDaemon(true);
        return thread;
    });
    private static ExtendedSurfaceMesh surfaces = new ExtendedSurfaceMesh();
    private static ExtendedVolumeMesh volumes = new ExtendedVolumeMesh();
    private record Pending(long detail, CompletableFuture<ExtendedVoxelMesher.Mesh> result) {}
    private static final Map<Long, Pending> pending = new HashMap<>();
    private static final long FRAME_MESH_BUDGET_NS = 5_000_000L;
    // No artificial GPU tile-count, byte or draw limits. Geometries are
    // actually recycled when offscreen and updated in-place when possible.
    private static long gpuVertexBytes;
    private static long renderFrame;
    private static long renderOriginX = Long.MIN_VALUE, renderOriginZ = Long.MIN_VALUE;
    private static final long OFFSCREEN_RECLAIM_FRAMES = 180;
    private static final Map<Long, Long> lastDrawn = new HashMap<>();
    private static final ExtendedVoxelAtlas ATLAS = new ExtendedVoxelAtlas();
    private static final int MAX_QUADS = ExtendedVoxelMesher.MAX_QUADS;
    private static final Map<Long, GpuTile> MESHES = new HashMap<>();
    private static final Set<Long> DIRTY = new HashSet<>();

    private record Candidate(long key, int priority, long distance) {}
    private static final PriorityQueue<Candidate> candidates = new PriorityQueue<>(
            Comparator.comparingInt(Candidate::priority).thenComparingLong(Candidate::distance));
    private static final Set<Long> queued = new HashSet<>();
    private static float queueLookX, queueLookZ;
    private static int queueX = Integer.MIN_VALUE, queueZ;
    private static final Matrix4f farProjection = new Matrix4f();
    private static final Matrix4f identityTexture = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();

    private static GpuDevice device;
    private static GpuBuffer quadIndices;
    private static GpuBuffer longProjection;
    private static final ByteBuffer PROJECTION_STAGING = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static long worldGeneration = Long.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static int lastRenderDistance;
    private static final Map<Long, Integer> readyColumns = new HashMap<>();
    private static int readyCursor;
    private static boolean failed;
    private static boolean loggedFirstUpload;
    private static boolean loggedFirstDraw;
    private static boolean loggedSharedTransform;

    private record GpuTile(GpuBuffer buffer, int quads, long detail, long bytes)
            implements AutoCloseable {
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
        if (ExtendedLodVertexSpace.needsRebase(renderOriginX, renderOriginZ,
                cameraX, cameraZ)) {
            clearMeshes();
            renderOriginX = (long)cx * 16L;
            renderOriginZ = (long)cz * 16L;
            centerX = centerZ = Integer.MIN_VALUE;
        }
        // A resource is released only if it has not been drawn for several
        // seconds. The exact block snapshot is retained on disk/in memory.
        if (renderFrame != 0 && renderFrame % 60 == 0) reclaimOffscreen();
        int distance = Math.max(2, mc.options.getEffectiveRenderDistance());
        int minTX = Math.floorDiv(cx - FAR, TILE), maxTX = Math.floorDiv(cx + FAR, TILE);
        int minTZ = Math.floorDiv(cz - FAR, TILE), maxTZ = Math.floorDiv(cz + FAR, TILE);
        updateNormalCoverage(mc, cx, cz, distance);
        if (centerX != cx || centerZ != cz || lastRenderDistance != distance) {
            boolean first = centerX == Integer.MIN_VALUE || lastRenderDistance != distance;
            centerX = cx; centerZ = cz; lastRenderDistance = distance;
            for (int tx = minTX; tx <= maxTX; tx++) for (int tz = minTZ; tz <= maxTZ; tz++) {
                long key = ExtendedVoxelStore.pack(tx, tz);
                if (outsideHorizon(tx, tz, cx, cz) || tileEntirelyInsideHandoff(tx, tz, cx, cz, distance)) continue;
                GpuTile current = MESHES.get(key);
                long wantedDetail = detailAt(tx, tz, cx, cz);
                // Fully generated chunks are NOT exempt from LOD changes.
                // Even an all-real tile must be re-meshed when the camera
                // crosses the 48/96-chunk resolution boundaries.
                boolean detailChanged = current != null && current.detail() != wantedDetail;
                if ((current == null || first || detailChanged) && hasChunkData(tx, tz)) {
                    markDirty(key);
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
                    accountAndClose(entry.getValue());
                    lastDrawn.remove(entry.getKey());
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
            for (int nz = Math.floorDiv(z - 1, TILE); nz <= Math.floorDiv(z + 1, TILE); nz++)
                for (int nx = Math.floorDiv(x - 1, TILE); nx <= Math.floorDiv(x + 1, TILE); nx++)
                    if (nx >= minTX && nx <= maxTX && nz >= minTZ && nz <= maxTZ)
                        markDirty(ExtendedVoxelStore.pack(nx, nz));
        }
        for (int i = 0; i < 256; i++) {
            Long key = ExtendedTerrainPreview.pollChanged();
            if (key == null) break;
            int x = (int)(key >> 32), z = (int)(long)key;
            if (x >= minTX && x <= maxTX && z >= minTZ && z <= maxTZ) markDirty(key);
        }
        try {
            finishMeshes(minTX, maxTX, minTZ, maxTZ, cx, cz);
            ensureIndices();
            // Pick the nearest visible dirty tile. Random HashSet iteration
            // caused important close LOD chunks to appear after far-off ones.
            var view = mc.gameRenderer.mainCamera().forwardVector();
            if (queueX != cx || queueZ != cz || Math.abs(queueLookX - view.x()) > .05f
                    || Math.abs(queueLookZ - view.z()) > .05f) {
                queueX = cx; queueZ = cz;
                queueLookX = view.x(); queueLookZ = view.z();
                queued.clear();
                candidates.clear();
                for (long key : DIRTY) enqueue(key);
            }
            long meshDeadline = System.nanoTime() + FRAME_MESH_BUDGET_NS;
            for (int rebuilt = 0; rebuilt < UPLOADS_PER_FRAME && pending.size() < 2; ) {
                var candidate = candidates.poll();
                if (candidate == null) break;
                long key = candidate.key();
                queued.remove(key);
                if (!DIRTY.contains(key) || pending.containsKey(key)) continue;
                int tx = (int)(key >> 32), tz = (int)key;
                if (outsideHorizon(tx, tz, cx, cz) || tileEntirelyInsideHandoff(tx, tz, cx, cz, distance)) continue;
                DIRTY.remove(key);
                scheduleTile(tx, tz, cx, cz);
                rebuilt++;
                // No hard eviction cap. Offscreen VBOs expire by visibility.
                if (System.nanoTime() >= meshDeadline) break;
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
                              TerrainRenderPass terrainPass, CameraTransform camera, GpuSampler terrainSampler) {
        if (terrainPass != DefaultTerrainRenderPasses.CUTOUT || pass == null
                || failed || MESHES.isEmpty() || quadIndices == null || longProjection == null) return;
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(ExtendedVoxelPipeline.TERRAIN));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", longProjection.slice());
            pass.setUniform("Sampler0", terrainPass.getAtlas(), terrainSampler);
            pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setIndexBuffer(quadIndices, IndexType.INT);
            var mc = Minecraft.getInstance();
            int cameraCX = ((int)Math.floor(camera.x)) >> 4, cameraCZ = ((int)Math.floor(camera.z)) >> 4;
            int distance = Math.max(2, mc.options.getEffectiveRenderDistance());
            float low = (float)(mc.level.getMinY() - camera.y);
            float high = low + mc.level.getHeight();
            frustum.set(new Matrix4f(farProjection).mul(matrices.modelView()), false);
            renderFrame++;
            // One DynamicTransforms allocation per FRAME, irrespective of
            // the number of tiles or the 32/64/128-chunk scene size.
            float originDx = (float)(renderOriginX - camera.x);
            float originDz = (float)(renderOriginZ - camera.z);
            var sharedView = new Matrix4f(matrices.modelView()).translate(
                    originDx, (float)-camera.y, originDz);
            var sharedTransform = RenderSystem.getDynamicUniforms().writeTransform(
                    sharedView, new Vector4f(1f, 1f, 1f, 0f),
                    new Vector3f(originDx, (float)-camera.y, originDz),
                    identityTexture);
            pass.setUniform("DynamicTransforms", sharedTransform);
            if (!loggedSharedTransform) {
                loggedSharedTransform = true;
                System.out.println("[SofronPack] Shared LOD GPU transform active; normal render "
                        + distance + " chunks, distant render " + FAR + " chunks");
            }
            // Frustum/filter first to avoid hidden tiles consuming draw calls.
            var ordered = new ArrayList<Map.Entry<Long, GpuTile>>();
            for (var entry : MESHES.entrySet()) {
                long key = entry.getKey();
                int tx = (int)(key >> 32), tz = (int)(long)key;
                float px = (float)((long)tx * TILE * 16 - camera.x);
                float pz = (float)((long)tz * TILE * 16 - camera.z);
                if (entry.getValue().quads == 0
                        || tileEntirelyInsideHandoff(tx, tz, cameraCX, cameraCZ, distance)
                        || !frustum.testAab(px, low, pz,
                                px + TILE * 16, high, pz + TILE * 16)) continue;
                ordered.add(entry);
            }
            ordered.sort(Comparator.comparingLong(entry ->
                    distanceSquared(entry.getKey(), cameraCX, cameraCZ)));
            for (Map.Entry<Long, GpuTile> entry : ordered) {
                long key = entry.getKey();
                int tx = (int)(key >> 32), tz = (int)(long)key;
                float x = (float)((long)tx * TILE * 16 - camera.x), z = (float)((long)tz * TILE * 16 - camera.z);
                if (!frustum.testAab(x, low, z, x + TILE * 16, high, z + TILE * 16)
                        || tileEntirelyInsideHandoff(tx, tz, cameraCX, cameraCZ, distance)) continue;
                GpuTile tile = entry.getValue();
                if (tile.quads == 0) continue;
                lastDrawn.put(key, renderFrame);
                int bytesPerQuad = 4 * ExtendedVoxelMesher.VERTEX_BYTES;
                for (int first = 0; first < tile.quads; first += MAX_QUADS) {
                    int count = Math.min(MAX_QUADS, tile.quads - first);
                    pass.setVertexBuffer(0, tile.buffer.slice((long)first * bytesPerQuad, (long)count * bytesPerQuad));
                    pass.drawIndexed(count * 6, 1, 0, 0, 0);
                }
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
    public static void updateProjection(Matrix4f terrainProjection) {
        if (device == null || MESHES.isEmpty() || failed || !ExtendedWorldConfig.get().enabled) return;
        Matrix4f projection = new Matrix4f(terrainProjection);
        // Leave the overlapping native geometry in front in the shared depth buffer.
        float near = 0.025f, far = FAR * 16f + 512f;
        boolean reversed = DepthStencilState.DEFAULT.depthTest()
                .equals(CompareOp.GREATER_THAN_OR_EQUAL);
        if (reversed) {
            float tmp = near; near = far; far = tmp;
        }
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        // Keep the X, Y and W clip coordinates from the actual terrain pass, including
        // view bobbing, camera tilt and portal effects. Only replace the depth row.
        float scale = (zeroToOne ? far : far + near) / (far - near);
        float offset = (zeroToOne ? far : far + far) * near / (near - far);
        projection.m02(scale * projection.m03()).m12(scale * projection.m13())
                .m22(scale * projection.m23()).m32(scale * projection.m33() + offset);
        if (longProjection != null && projection.equals(farProjection)) return;
        farProjection.set(projection);
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

    /** Keep distant coverage until Sodium has built the ordinary surface section. */
    private static void updateNormalCoverage(Minecraft mc, int cx, int cz, int near) {
        int minX = Math.floorDiv(cx - near, TILE), minZ = Math.floorDiv(cz - near, TILE);
        int width = Math.floorDiv(cx + near, TILE) - minX + 1;
        int depth = Math.floorDiv(cz + near, TILE) - minZ + 1;
        if (centerX != cx || centerZ != cz || lastRenderDistance != near) {
            readyColumns.keySet().removeIf(key -> {
                int x = (int)(key >> 32) - minX, z = (int)(long)key - minZ;
                return x < 0 || z < 0 || x >= width || z >= depth;
            });
        }
        var sodium = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
        for (int i = 0; i < Math.min(16, width * depth); i++) {
            int index = readyCursor = (readyCursor + 1) % (width * depth);
            int tx = minX + index % width, tz = minZ + index / width;
            int mask = 0;
            for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) {
                int chunkX = tx * TILE + x, chunkZ = tz * TILE + z;
                var chunk = mc.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null || sodium == null) continue;
                int surface = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 8, 8);
                if (sodium.isSectionReady(chunkX, surface >> 4, chunkZ)
                        && sodium.isBoxVisible(chunkX * 16, surface, chunkZ * 16,
                                chunkX * 16 + 16, surface + 1, chunkZ * 16 + 16)) mask |= 1 << (z * TILE + x);
            }
            long key = ExtendedVoxelStore.pack(tx, tz);
            Integer old = readyColumns.put(key, mask);
            if (mask != 0xffff && (old == null || old != mask)
                    && !MESHES.containsKey(key) && hasChunkData(tx, tz)) markDirty(key);
        }
    }

    private static boolean tileEntirelyInsideHandoff(int tx, int tz,
                                                       int cx, int cz, int renderDistance) {
        int left = tx * TILE - cx, right = (tx + 1) * TILE - cx;
        int top = tz * TILE - cz, bottom = (tz + 1) * TILE - cz;
        double maxX = Math.max(Math.abs(left), Math.abs(right));
        double maxZ = Math.max(Math.abs(top), Math.abs(bottom));
        return readyColumns.getOrDefault(ExtendedVoxelStore.pack(tx, tz), 0) == 0xffff
                && Math.hypot(maxX, maxZ)
                < pl.sofron.extended.ExtendedLodHandoff.nearStartChunks(renderDistance);
    }

    private static void markDirty(long key) {
        DIRTY.add(key);
        enqueue(key);
    }

    private static void enqueue(long key) {
        if (queueX == Integer.MIN_VALUE || queued.contains(key) || pending.containsKey(key)) return;
        int tx = (int)(key >> 32), tz = (int)key;
        if (outsideHorizon(tx, tz, queueX, queueZ)
                || !isInView(tx, tz, queueX * 16d + 8, queueZ * 16d + 8, queueLookX, queueLookZ)) return;
        int dx = tx * TILE + TILE / 2 - queueX, dz = tz * TILE + TILE / 2 - queueZ;
        int band = pl.sofron.extended.ExtendedLodStages.indexForRadius(Math.max(Math.abs(dx), Math.abs(dz)));
        int pass = band * 2 + (MESHES.containsKey(key) ? 1 : 0);
        queued.add(key);
        candidates.add(new Candidate(key, pass, (long)dx * dx + (long)dz * dz));
    }

    private static long detailAt(int tx, int tz, int cx, int cz) {
        long levels = 0;
        for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) {
            int ring = ExtendedLodLevels.ringForChunk(
                    tx * TILE + x, tz * TILE + z, cx, cz);
            levels |= (long)ring << (2 * (z * TILE + x));
        }
        return levels;
    }

    private static void scheduleTile(int tx, int tz, int cx, int cz) {
        long key = ExtendedVoxelStore.pack(tx, tz);
        long detail = detailAt(tx, tz, cx, cz);
        boolean preview = !MESHES.containsKey(key);

        var chunks = new ExtendedVoxelStore.VoxelChunk[(TILE + 2) * (TILE + 2)];
        // The old code left this array at all zeros, so fully generated
        // chunks ALWAYS rendered at 1x1 independent of camera distance.
        // Populate every source chunk (including 1-chunk borders), so
        // mixed-resolution seams can query the true neighbour resolution.
        int[] levels = new int[chunks.length];
        var predictions = new ExtendedTerrainPreview.Tile[chunks.length];
        int real = 0;
        int i = 0;
        for (int z = -1; z <= TILE; z++) for (int x = -1; x <= TILE; x++) {
            int chunkX = tx * TILE + x, chunkZ = tz * TILE + z;
            levels[i] = ExtendedLodLevels.ringForChunk(chunkX, chunkZ, cx, cz);
            chunks[i] = ExtendedVoxelStore.get(chunkX, chunkZ);
            if (chunks[i] == null) predictions[i] = ExtendedTerrainPreview.get(chunkX, chunkZ);
            else if (x >= 0 && z >= 0 && x < TILE && z < TILE) real++;
            i++;
        }
        var materials = ATLAS.snapshot(chunks);
        var surface = surfaces;
        var volume = volumes;
        boolean firstSurface = preview && real > 0 && real < TILE * TILE;
        if (firstSurface) detail = 0x1_aaaa_aaaaL;
        long resolution = real == 0 ? 0xaaaa_aaaaL : detail;
        int captured = real;
        var task = CompletableFuture.supplyAsync(() -> {
            if (firstSurface || captured == 0)
                return surface.build(resolution, chunks, materials, tx, tz, predictions);
            // Any finished chunk can refine independently of the other 15 in its tile.
            var exact = volume.build(chunks, levels, materials);
            if (captured == TILE * TILE) return exact;
            var remaining = surface.buildMissing(resolution, chunks, materials, tx, tz, predictions);
            if (remaining.quads() == 0) return exact;
            var combined = ByteBuffer.allocateDirect(exact.vertices().remaining() + remaining.vertices().remaining())
                    .order(ByteOrder.nativeOrder());
            combined.put(exact.vertices()).put(remaining.vertices()).flip();
            return new ExtendedVoxelMesher.Mesh(combined, exact.quads() + remaining.quads());
        }, WORKER);
        // Keep the actual per-chunk detail map even for completely generated
        // tiles. The previous -2 sentinel permanently locked them at 1x1.
        pending.put(key, new Pending(detail, task));
    }

    private static void finishMeshes(int minX, int maxX, int minZ, int maxZ, int cx, int cz) {
        var jobs = pending.entrySet().iterator();
        while (jobs.hasNext()) {
            var entry = jobs.next();
            var job = entry.getValue();
            if (!job.result.isDone()) continue;
            jobs.remove();
            long key = entry.getKey();
            int tx = (int)(key >> 32), tz = (int)key;
            if (tx < minX || tx > maxX || tz < minZ || tz > maxZ) continue;
            if (DIRTY.contains(key)) enqueue(key);
            var mesh = job.result.join();
            if (mesh.quads() == 0) {
                GpuTile previous = MESHES.remove(key);
                if (previous != null) accountAndClose(previous);
                lastDrawn.remove(key);
                continue;
            }
            // Bake the tile offset only on mesh updates, not on every GPU
            // frame. One shared transform now covers all visible LOD tiles.
            ExtendedLodVertexSpace.rebase(mesh.vertices(),
                    (long)tx * TILE * 16L, (long)tz * TILE * 16L,
                    renderOriginX, renderOriginZ);
            long requestedBytes = mesh.vertices().remaining();
            GpuTile previous = MESHES.get(key);
            GpuBuffer buffer;
            long capacity;
            if (previous != null && requestedBytes <= previous.bytes) {
                // In-place update avoids driver-side reallocations whenever
                // an edited tile or a new LOD mip fits its existing buffer.
                var encoder = device.createCommandEncoder();
                encoder.writeToBuffer(previous.buffer.slice(0, requestedBytes),
                        mesh.vertices());
                encoder.submit();
                buffer = previous.buffer;
                capacity = previous.bytes;
            } else {
                // Retire the old VBO BEFORE creating a bigger one, avoiding
                // the doubled peak during frequently rebuilt terrain.
                if (previous != null) {
                    MESHES.remove(key);
                    accountAndClose(previous);
                    lastDrawn.remove(key);
                }
                capacity = requestedBytes + Math.max(4096L, requestedBytes / 8L);
                buffer = device.createBuffer(() -> "SofronPack Extended World tile",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
                var encoder = device.createCommandEncoder();
                encoder.writeToBuffer(buffer.slice(0, requestedBytes), mesh.vertices());
                encoder.submit();
                gpuVertexBytes += capacity;
            }
            MESHES.put(key, new GpuTile(buffer, mesh.quads(), job.detail, capacity));
            lastDrawn.put(key, renderFrame);
            if (job.detail != detailAt(tx, tz, cx, cz)) markDirty(key);
            if (!loggedFirstUpload) {
                loggedFirstUpload = true;
                System.out.println("[SofronPack] Textured Extended World LOD GPU tile uploaded: "
                        + mesh.quads() + " square block faces");
            }
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

    private static boolean outsideHorizon(int tx, int tz, int cx, int cz) {
        int dx = Math.max(0, Math.abs(tx * TILE + TILE / 2 - cx) - TILE / 2 - 1);
        int dz = Math.max(0, Math.abs(tz * TILE + TILE / 2 - cz) - TILE / 2 - 1);
        return dx * dx + dz * dz > FAR * FAR;
    }

    private static boolean hasChunkData(int tx, int tz) {
        if (ExtendedTerrainPreview.get(tx * TILE, tz * TILE) != null) return true;
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

    private static long distanceSquared(long key, int cx, int cz) {
        long tx = (int)(key >> 32), tz = (int)key;
        long dx = tx * TILE + TILE / 2 - cx;
        long dz = tz * TILE + TILE / 2 - cz;
        return dx * dx + dz * dz;
    }

    private static void accountAndClose(GpuTile tile) {
        gpuVertexBytes -= tile.bytes;
        tile.close();
    }

    /**
     * Visibility-driven VBO reclamation, not a tile-count or memory quota.
     * Keep source snapshots and requeue geometry when the camera turns back.
     */
    private static void reclaimOffscreen() {
        Iterator<Map.Entry<Long, GpuTile>> it = MESHES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long key = entry.getKey();
            if (renderFrame - lastDrawn.getOrDefault(key, renderFrame)
                    < OFFSCREEN_RECLAIM_FRAMES) continue;
            accountAndClose(entry.getValue());
            it.remove();
            lastDrawn.remove(key);
            DIRTY.add(key);
            queued.remove(key); // Do not immediately reupload offscreen meshes.
        }
    }

    private static void clearMeshes() {
        for (Pending job : pending.values()) job.result.cancel(false);
        pending.clear();
        readyColumns.clear();
        surfaces = new ExtendedSurfaceMesh();
        volumes = new ExtendedVolumeMesh();
        for (GpuTile tile : MESHES.values()) tile.close();
        MESHES.clear();
        gpuVertexBytes = 0;
        lastDrawn.clear();
        renderFrame = 0;
        DIRTY.clear();
        candidates.clear();
        queued.clear();
        queueX = Integer.MIN_VALUE;
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
        renderOriginX = renderOriginZ = Long.MIN_VALUE;
        loggedFirstUpload = loggedFirstDraw = loggedSharedTransform = false;
    }
}
