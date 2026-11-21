package pl.sofron.extended.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
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
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.extended.ExtendedWorldMeshBuilder;
import pl.sofron.extended.ExtendedWorldStorage;

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
    private static final int TILE = ExtendedWorldMeshBuilder.TILE_CHUNKS;
    private static final int FAR = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final int UPLOADS_PER_FRAME = 3;
    private static final int MAX_QUADS = ExtendedWorldMeshBuilder.MAX_QUADS;
    private static final Map<Long, GpuTile> MESHES = new HashMap<>();
    private static final Set<Long> DIRTY = new HashSet<>();

    private static GpuDevice device;
    private static GpuBuffer quadIndices;
    private static long worldGeneration = Long.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static int lastRenderDistance;
    private static boolean failed;

    private record GpuTile(GpuBuffer buffer, int quads) implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }

    private ExtendedWorldLodRenderer() {}

    public static void render(RenderPass pass, ChunkRenderMatrices matrices,
                              TerrainRenderPass terrainPass, CameraTransform camera) {
        if (terrainPass != DefaultTerrainRenderPasses.CUTOUT || pass == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!ExtendedWorldConfig.get().enabled || mc.level == null
                || mc.getSingleplayerServer() == null || mc.player == null) {
            if (!MESHES.isEmpty()) close();
            return;
        }

        GpuDevice now = RenderSystem.getDevice();
        if (device != now || worldGeneration != ExtendedWorldStorage.generation()) {
            close();
            device = now;
            worldGeneration = ExtendedWorldStorage.generation();
        }
        if (failed || ExtendedWorldStorage.count() == 0) return;

        int cx = Math.floorDiv(camera.intX, 16);
        int cz = Math.floorDiv(camera.intZ, 16);
        int distance = Math.max(2, mc.options.getEffectiveRenderDistance());
        int minTX = Math.floorDiv(cx - FAR, TILE), maxTX = Math.floorDiv(cx + FAR, TILE);
        int minTZ = Math.floorDiv(cz - FAR, TILE), maxTZ = Math.floorDiv(cz + FAR, TILE);

        // Maintain a stable GPU cache: moving by one chunk does not cause
        // hundreds of distant surfaces to be rebuilt.
        if (centerX != cx || centerZ != cz || lastRenderDistance != distance) {
            boolean first = centerX == Integer.MIN_VALUE || lastRenderDistance != distance;
            centerX = cx; centerZ = cz; lastRenderDistance = distance;
            for (int tx = minTX; tx <= maxTX; tx++) for (int tz = minTZ; tz <= maxTZ; tz++) {
                long key = ExtendedWorldStorage.pack(tx, tz);
                if (!MESHES.containsKey(key) || first || intersectsMovingRings(tx, tz, cx, cz, distance))
                    DIRTY.add(key);
            }
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

        // The server publishes only changed source chunks, not full mesh data.
        for (int i = 0; i < 2048; i++) {
            Long changed = ExtendedWorldStorage.pollChanged();
            if (changed == null) break;
            int x = (int)(changed >> 32), z = (int)(long)changed;
            int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
            if (tx >= minTX && tx <= maxTX && tz >= minTZ && tz <= maxTZ) {
                DIRTY.add(ExtendedWorldStorage.pack(tx, tz));
            }
        }

        try {
            ensureIndices();
            int rebuilt = 0;
            Iterator<Long> pending = DIRTY.iterator();
            while (pending.hasNext() && rebuilt < UPLOADS_PER_FRAME) {
                long key = pending.next();
                pending.remove();
                int tx = (int)(key >> 32), tz = (int)key;
                if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ) continue;
                rebuildTile(tx, tz, cx, cz, distance);
                rebuilt++;
            }
            if (MESHES.isEmpty()) return;
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.DEBUG_QUADS));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(quadIndices, IndexType.INT);
            for (Map.Entry<Long, GpuTile> entry : MESHES.entrySet()) {
                long key = entry.getKey();
                int tx = (int)(key >> 32), tz = (int)key;
                if (tx < minTX || tx > maxTX || tz < minTZ || tz > maxTZ) continue;
                GpuTile tile = entry.getValue();
                if (tile.quads == 0) continue;
                Matrix4f relative = new Matrix4f(matrices.modelView()).translate(
                        (float)((long)tx * TILE * 16 - camera.x),
                        (float)-camera.y,
                        (float)((long)tz * TILE * 16 - camera.z));
                var transform = RenderSystem.getDynamicUniforms().writeTransform(
                        relative, new Vector4f(1f, 1f, 1f, 1f),
                        new Vector3f(), new Matrix4f(), 1f);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, tile.buffer.slice());
                pass.drawIndexed(tile.quads * 6, 1, 0, 0, 0);
            }
        } catch (RuntimeException | LinkageError problem) {
            failed = true;
            System.err.println("[SofronPack] Native Extended World renderer disabled: "
                    + problem.getMessage());
            close();
            failed = true;
        }
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
        var mesh = ExtendedWorldMeshBuilder.build(tx, tz, cx, cz, near, FAR, ExtendedWorldStorage::get);
        GpuTile previous = MESHES.remove(ExtendedWorldStorage.pack(tx, tz));
        if (previous != null) previous.close();
        if (mesh.quadCount() == 0) return;
        GpuBuffer buffer = device.createBuffer(() -> "SofronPack Extended World tile",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertices());
        MESHES.put(ExtendedWorldStorage.pack(tx, tz), new GpuTile(buffer, mesh.quadCount()));
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

    /** Release all device resources from the render thread. */
    public static void close() {
        for (GpuTile tile : MESHES.values()) tile.close();
        MESHES.clear();
        DIRTY.clear();
        if (quadIndices != null) quadIndices.close();
        quadIndices = null;
        device = null;
        worldGeneration = Long.MIN_VALUE;
        centerX = centerZ = Integer.MIN_VALUE;
        lastRenderDistance = 0;
    }
}
