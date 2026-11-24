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
import java.nio.file.Path;
import pl.sofron.extended.ExtendedLodMeshCache;
import pl.sofron.extended.ExtendedLodMeshFingerprint;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.HashMap;
import java.util.ArrayDeque;
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
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.extended.ExtendedLodLevels;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import pl.sofron.extended.ExtendedVoxelMesher;
import pl.sofron.extended.ExtendedPackedQuads;
import pl.sofron.extended.ExtendedVoxelStore;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.FilterMode;

/**
 * Native RenderPearl (OpenGL + Vulkan) renderer of compact distant surface
 * tiles. The renderer is a client component of SofronPack itself: it does not
 * discover or load a separate long-range renderer.
 *
 * Four-by-four-chunk tiles share paginated GPU buffers, storing one packed
 * 48-byte instance for each quad. Source changes and camera-distance band
 * crossings invalidate geometry; native render distance stays unchanged.
 * Meshing/uploads still use small time budgets, never a visibility cap.
 */
public final class ExtendedWorldLodRenderer {
    private static final int TILE = ExtendedVoxelMesher.TILE_CHUNKS;
    private static final int FAR = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    // Cached meshes only require disk decoding + GPU upload. Submit more of
    // them on re-entry without allocating all 128 chunks in a single frame.
    private static final int JOBS_PER_FRAME = 12;
    private static final int MAX_PENDING = 16;
    private static final int GPU_UPLOADS_PER_FRAME = 4;
    // Prevent first-frame GPU spikes when restoring many large forest meshes.
    private static final int GPU_BYTES_PER_FRAME = 8 * 1024 * 1024;
    private static final long GPU_UPLOAD_TIME_BUDGET_NS = 2_000_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "SofronPack-LOD-Mesh");
        thread.setDaemon(true);
        return thread;
    });
    private static ExtendedSurfaceMesh surfaces = new ExtendedSurfaceMesh();
    private static ExtendedVolumeMesh volumes = new ExtendedVolumeMesh();
    private record Pending(long detail, CompletableFuture<ExtendedLodMeshCache.Packed> result) {}
    private static final Map<Long, Pending> pending = new HashMap<>();
    private static final long FRAME_MESH_BUDGET_NS = 5_000_000L;
    // No artificial 20-chunk normal view cap or 128/256 MiB GPU quotas:
    // dense geometry is halved and pooled, not hidden when a cap is hit.
    private static final ExtendedVoxelAtlas ATLAS = new ExtendedVoxelAtlas();
    private static GpuBuffer overviewBuffer;
    private static ExtendedTerrainPreview.Overview uploadedOverview;
    private static final int MAX_QUADS = ExtendedVoxelMesher.MAX_QUADS;
    private static final Long2ObjectOpenHashMap<GpuTile> MESHES = new Long2ObjectOpenHashMap<>();
    private static final Set<Long> DIRTY = new HashSet<>();
    // Transient terrain predictions do not have a persistent world fingerprint.
    // If a newer preview arrives, rebuild it rather than replaying an older
    // preview mesh that had the same captured voxel neighbours.
    private static final Set<Long> REFRESHED_PREVIEWS = new HashSet<>();

    private record Candidate(long key, int priority, long distance, boolean cached) {}
    // On re-entry show *already rendered* areas before building new terrain.
    // With cache misses, preserve the original distance/stage ordering.
    private static final PriorityQueue<Candidate> candidates = new PriorityQueue<>(
            Comparator.comparing((Candidate c) -> !c.cached())
                    .thenComparingInt(Candidate::priority).thenComparingLong(Candidate::distance));
    // Cached meshes need to shrink as the player travels AWAY from them, even
    // when those chunks are behind the camera and are no longer in the view cone.
    private static final PriorityQueue<Candidate> reductions = new PriorityQueue<>(
            Comparator.comparing((Candidate c) -> !c.cached())
                    .thenComparingLong(Candidate::distance).thenComparingInt(Candidate::priority));
    private static boolean scheduleReductionNext;
    private static final Set<Long> queued = new HashSet<>();
    private static float queueLookX, queueLookZ;
    private static int queueX = Integer.MIN_VALUE, queueZ;
    private static final Matrix4f clipFromWorld = new Matrix4f();
    private static final Matrix4f farProjection = new Matrix4f();
    private static final Matrix4f identityTexture = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();

    private static GpuDevice device;
    private static ExtendedGpuGeometryArena geometryArena;
    private static GpuBuffer quadIndices;
    private static GpuBuffer longProjection;
    private static final ByteBuffer PROJECTION_STAGING = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    private static long worldGeneration = Long.MIN_VALUE;
    private static long lastMeshIndexGeneration = Long.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static int lastRenderDistance;

    // Never traverse all 4,000+ distant tiles in a single render frame.
    // A full horizon scan is spread over many frames. New camera positions
    // request another pass without aborting an already-progressing scan.
    private static final int HORIZON_TILES_PER_FRAME = 128;
    // Scan from the player outward, so cached nearby terrain appears first,
    // rather than waiting for half of a row-major 128-chunk horizon pass.
    private static final long[] HORIZON_ORDER =
            pl.sofron.extended.ExtendedWorldPrefetch.spiralOffsets(FAR / TILE + 1);
    private static final long HORIZON_SCAN_TIME_NS = 1_500_000L;
    private static boolean horizonScanning, horizonAgain, horizonForce;
    private static int horizonCursor, horizonAnchorTX, horizonAnchorTZ;
    private static final ArrayDeque<Long> expiredMeshes = new ArrayDeque<>();
    private static final ArrayDeque<Long> expiredDirty = new ArrayDeque<>();
    private static final ArrayList<Long> reprioritize = new ArrayList<>();
    private static int reprioritizeCursor;
    private static final Long2IntOpenHashMap readyColumns = new Long2IntOpenHashMap();
    private static int readyCursor;
    private static boolean failed;
    private static boolean loggedFirstUpload;
    private static boolean loggedFirstDraw;
    private static boolean loggedFirstDiskRestore;
    private static boolean loggedFirstDiskSave;

    private record GpuTile(ExtendedGpuGeometryArena.Region region, GpuBufferSlice vertices, int quads, long detail)
            implements AutoCloseable {
        @Override public void close() { if (region != null) region.close(); }
    }

    private ExtendedWorldLodRenderer() {}

    /**
     * Called before Minecraft opens any RenderPass. GPU uploads and buffer
     * retirement must never happen inside a live Vulkan render pass.
     */
    public static void prepareFrame(double cameraX, double cameraZ) {
        long start = System.nanoTime();
        try {
            prepareFrameInternal(cameraX, cameraZ);
        } finally {
            pl.sofron.optimization.SofronFrameTiming.reportIfSlow(
                    "LOD GPU preparation", start, 25_000_000L);
        }
    }

    private static void prepareFrameInternal(double cameraX, double cameraZ) {
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
        Path meshCache = ExtendedVoxelStore.meshCacheDirectory();
        long indexRevision = ExtendedLodMeshCache.indexGeneration(meshCache);
        boolean indexReady = indexRevision != Long.MIN_VALUE
                && indexRevision != lastMeshIndexGeneration;
        if (indexReady) lastMeshIndexGeneration = indexRevision;
        var overview = ExtendedTerrainPreview.overview();
        if (overview == null && ExtendedVoxelStore.count() == 0 && !ExtendedLodMeshCache.exists(meshCache)) return;
        if (ATLAS.refreshModels()) {
            clearMeshes();
            centerX = centerZ = Integer.MIN_VALUE;
        }

        if (overview != null && overview != uploadedOverview) uploadOverview(overview);
        int cx = ((int)Math.floor(cameraX)) >> 4;
        int cz = ((int)Math.floor(cameraZ)) >> 4;
        int distance = Math.max(2, mc.options.getEffectiveRenderDistance());
        int minTX = Math.floorDiv(cx - FAR, TILE), maxTX = Math.floorDiv(cx + FAR, TILE);
        int minTZ = Math.floorDiv(cz - FAR, TILE), maxTZ = Math.floorDiv(cz + FAR, TILE);
        updateNormalCoverage(mc, cx, cz, distance);
        boolean moved = centerX != cx || centerZ != cz;
        boolean changedDistance = lastRenderDistance != distance;
        if (moved || changedDistance || indexReady) {
            boolean force = centerX == Integer.MIN_VALUE || changedDistance;
            centerX = cx; centerZ = cz; lastRenderDistance = distance;
            if (!horizonScanning) {
                beginHorizonScan(cx, cz, force);
            } else {
                horizonAgain = true;
            }
            if (moved || changedDistance) {
                // Copy keys only; individual GPU buffers are retired gradually.
                if (expiredMeshes.isEmpty()) expiredMeshes.addAll(MESHES.keySet());
                if (expiredDirty.isEmpty()) expiredDirty.addAll(DIRTY);
            }
        }
        scanHorizon(meshCache, minTX, maxTX, minTZ, maxTZ, cx, cz, distance);
        retireOutOfRange(minTX, maxTX, minTZ, maxTZ);

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
            if (x >= minTX && x <= maxTX && z >= minTZ && z <= maxTZ) {
                REFRESHED_PREVIEWS.add(key);
                markDirty(key);
            }
        }
        try {
            finishMeshes(minTX, maxTX, minTZ, maxTZ, cx, cz);
            ensureIndices();
            // Pick the nearest visible dirty tile. Random HashSet iteration
            // caused important close LOD chunks to appear after far-off ones.
            var view = mc.gameRenderer.mainCamera().forwardVector();
            // Do not re-sort thousands of dirty tiles for every one-chunk
            // movement or tiny mouse turn. Rebuild in bounded slices.
            boolean reorient = queueX == Integer.MIN_VALUE
                    || Math.abs(queueX - cx) >= 4 || Math.abs(queueZ - cz) >= 4
                    || Math.abs(queueLookX - view.x()) > .30f
                    || Math.abs(queueLookZ - view.z()) > .30f;
            if (reorient && reprioritizeCursor >= reprioritize.size()) {
                queueX = cx; queueZ = cz;
                queueLookX = view.x(); queueLookZ = view.z();
                queued.clear();
                candidates.clear();
                reductions.clear();
                reprioritize.clear();
                reprioritize.addAll(DIRTY);
                reprioritizeCursor = 0;
            }
            long priorityStart = System.nanoTime();
            for (int scanned = 0;
                    reprioritizeCursor < reprioritize.size() && scanned < 128;
                    scanned++) {
                if (scanned > 0 && System.nanoTime() - priorityStart >= 1_500_000L)
                    break;
                enqueue(reprioritize.get(reprioritizeCursor++));
            }
            if (reprioritizeCursor >= reprioritize.size()) {
                reprioritize.clear();
                reprioritizeCursor = 0;
            }
            long meshDeadline = System.nanoTime() + FRAME_MESH_BUDGET_NS;
            int freshJobs = 0;
            for (int rebuilt = 0; rebuilt < JOBS_PER_FRAME && pending.size() < MAX_PENDING; ) {
                var candidate = pollCandidate();
                if (candidate == null) break;
                long key = candidate.key();
                queued.remove(key);
                if (!DIRTY.contains(key) || pending.containsKey(key)) continue;
                int tx = (int)(key >> 32), tz = (int)key;
                if (outsideHorizon(tx, tz, cx, cz) || tileEntirelyInsideHandoff(tx, tz, cx, cz, distance)) continue;
                // Preserve the old two-new-mesh-per-frame budget while allowing
                // up to twelve already-saved LOD tiles to be restored promptly.
                boolean cached = candidate.cached();
                if (!cached && freshJobs >= 2) {
                    enqueue(key);
                    break;
                }
                DIRTY.remove(key);
                scheduleTile(tx, tz, cx, cz);
                rebuilt++;
                if (!cached) freshJobs++;
                // The shared arena reuses freed space; no fixed tile eviction.
                if (System.nanoTime() >= meshDeadline) break;
            }
        } catch (RuntimeException | LinkageError problem) {
            System.err.println("[SofronPack] Extended World GPU preparation failed: "
                    + problem.getMessage());
            close();
            failed = true;
        }
    }

    private static void uploadOverview(ExtendedTerrainPreview.Overview overview) {
        int side = ExtendedTerrainPreview.Overview.SIDE;
        int stride = ExtendedPackedQuads.QUAD_BYTES;
        ByteBuffer data = ByteBuffer.allocateDirect(side * side * stride).order(ByteOrder.nativeOrder());
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int material = overview.material(x, z);
            var uv = ATLAS.face(ExtendedTerrainPreview.MATERIALS[material], net.minecraft.core.Direction.UP);
            if (uv == null) return;
            for (int corner = 0; corner < 4; corner++) {
                int dx = corner >= 2 ? 1 : 0, dz = corner == 1 || corner == 2 ? 1 : 0;
                int h = Math.max(overview.sea, overview.height(x + dx, z + dz));
                data.putShort((short)(dx * 64 * 32)).putShort((short)(Math.clamp(h, -1024, 1023) * 32))
                        .putShort((short)(dz * 64 * 32));
            }
            int rgb = uv.tint() == -1 ? overview.tint(x, z, material) : uv.tint();
            data.putFloat(ExtendedSurfaceMesh.packUv(uv.u0(), uv.u1()))
                    .putFloat(ExtendedSurfaceMesh.packUv(uv.v0(), uv.v1())).putLong(0L);
            data.putInt((rgb >>> 16 & 255) | (rgb & 0xff00) | ((rgb & 255) << 16)
                    | ((254 - 3 * uv.emission()) << 24)).putInt(1);
        }
        var buffer = device.createBuffer(() -> "SofronPack opening horizon",
                GpuBuffer.USAGE_VERTEX, data.flip());
        MESHES.values().removeIf(tile -> tile.region == null);
        if (overviewBuffer != null) overviewBuffer.close();
        overviewBuffer = buffer;
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            long key = ExtendedVoxelStore.pack(overview.tileX + x, overview.tileZ + z);
            if (!MESHES.containsKey(key)) MESHES.put(key, new GpuTile(null,
                    buffer.slice((z * side + x) * stride, stride), 1, -1));
        }
        uploadedOverview = overview;
    }

    private static void beginHorizonScan(int cx, int cz, boolean force) {
        horizonScanning = true;
        horizonAgain = false;
        horizonForce = force;
        horizonCursor = 0;
        horizonAnchorTX = Math.floorDiv(cx, TILE);
        horizonAnchorTZ = Math.floorDiv(cz, TILE);
    }

    private static void scanHorizon(Path meshCache, int minTX, int maxTX,
                                    int minTZ, int maxTZ, int cx, int cz, int distance) {
        if (!horizonScanning) return;
        long start = System.nanoTime();
        for (int scanned = 0; horizonCursor < HORIZON_ORDER.length
                && scanned < HORIZON_TILES_PER_FRAME; scanned++) {
            if (scanned > 0 && System.nanoTime() - start >= HORIZON_SCAN_TIME_NS)
                break;
            long offset = HORIZON_ORDER[horizonCursor++];
            int tx = horizonAnchorTX + (int)(offset >> 32);
            int tz = horizonAnchorTZ + (int)offset;
            if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ
                    || outsideHorizon(tx, tz, cx, cz)
                    || tileEntirelyInsideHandoff(tx, tz, cx, cz, distance)) continue;
            long key = ExtendedVoxelStore.pack(tx, tz);
            GpuTile current = MESHES.get(key);
            long detail = detailAt(tx, tz, cx, cz);
            if ((current == null || horizonForce || current.detail() != detail)
                    && (hasChunkData(tx, tz)
                        || ExtendedLodMeshCache.hasSaved(meshCache, tx, tz, detail))) {
                markDirty(key);
            }
        }
        if (horizonCursor >= HORIZON_ORDER.length) {
            horizonScanning = false;
            if (horizonAgain)
                beginHorizonScan(cx, cz, false);
        }
    }

    private static void retireOutOfRange(int minTX, int maxTX, int minTZ, int maxTZ) {
        long start = System.nanoTime();
        for (int retired = 0; retired < 128; retired++) {
            if (retired > 0 && System.nanoTime() - start >= 1_500_000L) break;
            if (!expiredMeshes.isEmpty()) {
                long key = expiredMeshes.removeFirst();
                int tx = (int)(key >> 32), tz = (int)key;
                if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ) {
                    GpuTile tile = MESHES.remove(key);
                    if (tile != null) tile.close();
                }
            } else if (!expiredDirty.isEmpty()) {
                long key = expiredDirty.removeFirst();
                int tx = (int)(key >> 32), tz = (int)key;
                if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ)
                    DIRTY.remove(key);
            } else {
                break;
            }
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
            frustum.set(farProjection.mul(matrices.modelView(), clipFromWorld), false);
            for (var it = MESHES.long2ObjectEntrySet().fastIterator(); it.hasNext();) {
                var entry = it.next();
                long key = entry.getLongKey();
                int tx = (int)(key >> 32), tz = (int)(long)key;
                float x = (float)((long)tx * TILE * 16 - camera.x), z = (float)((long)tz * TILE * 16 - camera.z);
                if (!frustum.testAab(x, low, z, x + TILE * 16, high, z + TILE * 16)) continue;
                float nearestX = Math.max(0f, Math.max(x, -x - TILE * 16));
                float nearestZ = Math.max(0f, Math.max(z, -z - TILE * 16));
                if (nearestX * nearestX + nearestZ * nearestZ >= (FAR * 16) * (FAR * 16)) continue;
                int readyMask = readyColumns.get(key);
                if (readyMask == 0xffff && insideHandoff(tx, tz, cameraCX, cameraCZ, distance)) continue;
                GpuTile tile = entry.getValue();
                if (tile.quads == 0) continue;
                Matrix4f relative = new Matrix4f(matrices.modelView()).translate(
                        x, (float)-camera.y, z);
                var transform = RenderSystem.getDynamicUniforms().writeTransform(
                        // Full material alpha: fog is now calculated
                        // exclusively from the shared Minecraft Fog uniforms.
                        relative, new Vector4f(1f, 1f, 1f, readyMask),
                        new Vector3f(x, (float)-camera.y, z),
                        identityTexture);
                pass.setUniform("DynamicTransforms", transform);
                // One packed quad = one GPU instance. Six shared indices
                // emit the four reconstructed corners in the vertex shader.
                // Each tile uses just ONE draw, even for >32,768 quads.
                pass.setVertexBuffer(0, tile.vertices);
                pass.drawIndexed(6, tile.quads, 0, 0, 0);
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
        Matrix4f projection = clipFromWorld.set(terrainProjection);
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
            readyColumns.keySet().removeIf((long key) -> {
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
            int old = readyColumns.getOrDefault(key, -1);
            if (old != mask) readyColumns.put(key, mask);
            if (mask != 0xffff && old != mask
                    && !MESHES.containsKey(key) && hasChunkData(tx, tz)) markDirty(key);
        }
    }

    private static boolean tileEntirelyInsideHandoff(int tx, int tz,
                                                       int cx, int cz, int renderDistance) {
        return readyColumns.getOrDefault(ExtendedVoxelStore.pack(tx, tz), 0) == 0xffff
                && insideHandoff(tx, tz, cx, cz, renderDistance);
    }

    private static boolean insideHandoff(int tx, int tz, int cx, int cz, int renderDistance) {
        int left = tx * TILE - cx, right = (tx + 1) * TILE - cx;
        int top = tz * TILE - cz, bottom = (tz + 1) * TILE - cz;
        double maxX = Math.max(Math.abs(left), Math.abs(right));
        double maxZ = Math.max(Math.abs(top), Math.abs(bottom));
        double start = pl.sofron.extended.ExtendedLodHandoff.nearStartChunks(renderDistance);
        return maxX * maxX + maxZ * maxZ < start * start;
    }

    private static void markDirty(long key) {
        DIRTY.add(key);
        enqueue(key);
    }

    private static void enqueue(long key) {
        if (queueX == Integer.MIN_VALUE || queued.contains(key) || pending.containsKey(key)) return;
        int tx = (int)(key >> 32), tz = (int)key;
        if (outsideHorizon(tx, tz, queueX, queueZ)) return;
        GpuTile current = MESHES.get(key);
        boolean reduction = current != null && ExtendedLodLevels.requiresCoarsening(
                current.detail(), detailAt(tx, tz, queueX, queueZ));
        // New geometry and upgrades are view-prioritised. Downgrades must
        // also run behind the player or old 1x1 GPU meshes never get reduced.
        if (!reduction && !isInView(tx, tz, queueX * 16d + 8,
                queueZ * 16d + 8, queueLookX, queueLookZ)) return;
        int dx = tx * TILE + TILE / 2 - queueX, dz = tz * TILE + TILE / 2 - queueZ;
        int band = pl.sofron.extended.ExtendedLodStages.indexForRadius(Math.max(Math.abs(dx), Math.abs(dz)));
        int pass = band * 2 + (current != null ? 1 : 0);
        boolean cached = ExtendedLodMeshCache.hasSaved(
                ExtendedVoxelStore.meshCacheDirectory(), tx, tz, detailAt(tx, tz, queueX, queueZ));
        queued.add(key);
        Candidate candidate = new Candidate(key, pass, (long)dx * dx + (long)dz * dz, cached);
        if (reduction) reductions.add(candidate);
        else candidates.add(candidate);
    }

    private static Candidate pollCandidate() {
        // Reserve every other submitted mesh job for distance reductions.
        // This is independent of which direction the player looks, so a
        // continuous flow of newly generated tiles cannot starve downgrades.
        Candidate next = !reductions.isEmpty() && (candidates.isEmpty() || scheduleReductionNext)
                ? reductions.poll() : candidates.poll();
        if (next == null) next = reductions.poll();
        if (next != null) scheduleReductionNext = !scheduleReductionNext;
        return next;
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

    /**
     * Two bits per chunk already cover all four LOD rings (0..3).
     * Keep incomplete previews >=4x4 nearby, and >=8x8 in the farthest
     * ring, without changing persistent generated chunk resolution.
     */
    private static long previewDetailAt(int tx, int tz, int cx, int cz) {
        long result = 0;
        for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) {
            int level = ExtendedLodLevels.ringForChunk(
                    tx * TILE + x, tz * TILE + z, cx, cz);
            result |= (long)Math.max(2, level) << (2 * (z * TILE + x));
        }
        return result;
    }

    private static void scheduleTile(int tx, int tz, int cx, int cz) {
        long key = ExtendedVoxelStore.pack(tx, tz);
        long detail = detailAt(tx, tz, cx, cz);
        GpuTile resident = MESHES.get(key);
        boolean preview = resident == null || resident.region == null;
        // On first re-entry, display the saved preview immediately. After it
        // reaches the GPU, refresh it using any newly sampled live preview.
        // Existing on-screen tiles still update directly as before.
        boolean refreshedPreview = REFRESHED_PREVIEWS.contains(key) && !preview;
        if (refreshedPreview) REFRESHED_PREVIEWS.remove(key);
        Path meshCache = ExtendedVoxelStore.meshCacheDirectory();

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
        if (firstSurface) detail = previewDetailAt(tx, tz, cx, cz);
        // The first terrain preview is intentionally 4x4 near the camera,
        // but 8x8 beyond 96 chunks: even a transient preview must respect
        // the very coarse farthest display ring.
        long resolution = real == 0 ? previewDetailAt(tx, tz, cx, cz) : detail;
        int captured = real;
        long savedDetail = detail;
        var task = CompletableFuture.supplyAsync(() -> {
            byte[] fingerprint = ExtendedLodMeshFingerprint.of(chunks, materials);
            if (!refreshedPreview) {
                var restored = ExtendedLodMeshCache.load(meshCache, tx, tz, savedDetail, fingerprint);
                if (restored != null) {
                    if (!loggedFirstDiskRestore) {
                        loggedFirstDiskRestore = true;
                        System.out.println("[SofronPack] Persistent LOD: restored packed mesh from world save");
                    }
                    return restored;
                }
            }
            ExtendedVoxelMesher.Mesh result;
            if (firstSurface || captured == 0) {
                result = surface.build(resolution, chunks, materials, tx, tz, predictions);
            } else {
                // Any finished chunk can refine independently of the other 15 in its tile.
                var exact = volume.build(chunks, levels, materials);
                if (captured == TILE * TILE) {
                    result = exact;
                } else {
                    var remaining = surface.buildMissing(resolution, chunks, materials, tx, tz, predictions);
                    if (remaining.quads() == 0) {
                        result = exact;
                    } else {
                        var combined = ByteBuffer.allocateDirect(exact.vertices().remaining() + remaining.vertices().remaining())
                                .order(ByteOrder.nativeOrder());
                        combined.put(exact.vertices()).put(remaining.vertices()).flip();
                        result = new ExtendedVoxelMesher.Mesh(combined, exact.quads() + remaining.quads());
                    }
                }
            }
            // Save *the display geometry* as well as its 1x1 source snapshot:
            // all four LOD bands (including mixed-resolution boundaries) can
            // be replayed directly at the next launch without re-meshing.
            ByteBuffer packed = ExtendedPackedQuads.pack(result.vertices());
            var ready = new ExtendedLodMeshCache.Packed(packed, result.quads());
            // Render the finished tile immediately; compress and write its
            // persistent cache on a separate bounded low-priority I/O worker.
            ExtendedLodMeshCache.saveDeferred(meshCache, tx, tz, savedDetail, fingerprint, ready);
            if (meshCache != null && !loggedFirstDiskSave) {
                loggedFirstDiskSave = true;
                System.out.println("[SofronPack] Persistent LOD: queued compressed mesh save");
            }
            return ready;
        }, WORKER);
        // Keep the actual per-chunk detail map even for completely generated
        // tiles. The previous -2 sentinel permanently locked them at 1x1.
        pending.put(key, new Pending(detail, task));
    }

    private static void finishMeshes(int minX, int maxX, int minZ, int maxZ, int cx, int cz) {
        var jobs = pending.entrySet().iterator();
        int uploaded = 0, transferredBytes = 0;
        long startUpload = System.nanoTime();
        while (jobs.hasNext() && uploaded < GPU_UPLOADS_PER_FRAME) {
            if (uploaded > 0 && System.nanoTime() - startUpload >= GPU_UPLOAD_TIME_BUDGET_NS)
                break; // GPU work is amortized over frames, never discarded.
            var entry = jobs.next();
            var job = entry.getValue();
            if (!job.result.isDone()) continue;
            var mesh = job.result.join();
            int bytes = mesh.vertices().remaining();
            if (uploaded > 0 && transferredBytes + (long) bytes > GPU_BYTES_PER_FRAME)
                continue; // Keep the completed job for a following render frame.
            jobs.remove();
            long key = entry.getKey();
            int tx = (int)(key >> 32), tz = (int)key;
            if (tx < minX || tx > maxX || tz < minZ || tz > maxZ) continue;
            if (DIRTY.contains(key)) enqueue(key);
            uploaded++;
            transferredBytes += bytes;
            if (mesh.quads() == 0) {
                GpuTile existing = MESHES.get(key);
                if (existing != null && existing.region == null && !hasChunkData(tx, tz)) continue;
                GpuTile previous = MESHES.remove(key);
                if (previous != null) previous.close();
                if (!DIRTY.contains(key)) REFRESHED_PREVIEWS.remove(key);
                continue;
            }
            // Already packed on the worker, or loaded from the world folder.
            // No 3D meshing or compression on the render/GPU upload thread.
            ByteBuffer packed = mesh.vertices();
            if (geometryArena == null) geometryArena = new ExtendedGpuGeometryArena(device);
            GpuTile previous = MESHES.get(key);
            ExtendedGpuGeometryArena.Region region;
            if (previous != null && previous.region != null && previous.region.size() >= packed.remaining()
                    && previous.region.size() <= (long)packed.remaining() * 2) {
                // Reuse similarly sized regions to avoid needless GPU churn.
                region = previous.region;
            } else {
                // Also retire OVERSIZED regions when moving to a coarser mip:
                // otherwise the geometry shrinks visually but stays at 1x1
                // allocation size forever. Release BEFORE allocating again
                // to prevent a temporary double-VRAM spike.
                if (previous != null) {
                    MESHES.remove(key);
                    previous.close();
                }
                region = geometryArena.allocate(packed.remaining());
            }
            // Keep the original buffer view stable until deferred cache I/O
            // finishes, even if the GPU backend consumes the upload view.
            region.upload(packed.duplicate());
            MESHES.put(key, new GpuTile(region, region.slice(packed.remaining()), mesh.quads(), job.detail));
            if (mesh.fromDisk()) {
                // Restore the previously visible terrain first; then, if a
                // transient terrain preview has changed, reconcile it without
                // leaving a gap during the startup frame.
                if (REFRESHED_PREVIEWS.contains(key)) markDirty(key);
            } else if (!DIRTY.contains(key)) {
                REFRESHED_PREVIEWS.remove(key);
            }
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
        ByteBuffer buffer = ByteBuffer.allocateDirect(6 * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        buffer.putInt(0).putInt(1).putInt(2)
                .putInt(2).putInt(3).putInt(0);
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

    private static void clearMeshes() {
        for (Pending job : pending.values()) job.result.cancel(false);
        pending.clear();
        readyColumns.clear();
        surfaces = new ExtendedSurfaceMesh();
        volumes = new ExtendedVolumeMesh();
        for (GpuTile tile : MESHES.values()) tile.close();
        MESHES.clear();
        if (overviewBuffer != null) overviewBuffer.close();
        overviewBuffer = null;
        uploadedOverview = null;
        if (geometryArena != null) geometryArena.close();
        geometryArena = null;
        DIRTY.clear();
        REFRESHED_PREVIEWS.clear();
        candidates.clear();
        reductions.clear();
        scheduleReductionNext = false;
        queued.clear();
        reprioritize.clear();
        reprioritizeCursor = 0;
        expiredMeshes.clear();
        expiredDirty.clear();
        horizonScanning = horizonAgain = horizonForce = false;
        horizonCursor = 0;
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
        lastMeshIndexGeneration = Long.MIN_VALUE;
        worldGeneration = Long.MIN_VALUE;
        centerX = centerZ = Integer.MIN_VALUE;
        lastRenderDistance = 0;
        loggedFirstUpload = loggedFirstDraw = loggedFirstDiskRestore = loggedFirstDiskSave = false;
    }
}
