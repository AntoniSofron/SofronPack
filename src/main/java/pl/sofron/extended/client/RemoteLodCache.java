package pl.sofron.extended.client;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import pl.sofron.extended.ExtendedVoxelStore;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.extended.mixin.BiomeZoomSeedAccessor;

/** Client-owned snapshots of chunks received through the ordinary multiplayer connection. */
public final class RemoteLodCache {
    private static final int MAX_COPIES = 8;
    private static final Semaphore copies = new Semaphore(MAX_COPIES);
    private static final ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SofronPack-Remote-LOD");
        thread.setDaemon(true);
        return thread;
    });
    private static final LinkedHashSet<LevelChunk> pending = new LinkedHashSet<>();
    private static ClientLevel active;

    private RemoteLodCache() {}

    public static boolean canRender(Minecraft client, ClientLevel level) {
        return client.getSingleplayerServer() != null || (level != null && level == active);
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> changed(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level == active && pending.remove(chunk)) submit(chunk);
        });
        ClientTickEvents.END_CLIENT_TICK.register(RemoteLodCache::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close());
    }

    public static void changed(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ClientLevel level && level == active) pending.add(chunk);
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (active != null && (active != level || !ExtendedWorldConfig.get().enabled)) close();
        if (level == null || client.player == null || client.getSingleplayerServer() != null
                || !ExtendedWorldConfig.get().enabled) return;
        if (active == null) {
            var server = client.getCurrentServer();
            if (server == null) return;
            long seed = ((BiomeZoomSeedAccessor) level.getBiomeManager()).sofronpack$worldSeedHash();
            String dimension = level.dimension().identifier().toString();
            String identity = server.ip + "\n" + seed + "\n" + dimension;
            var key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            ExtendedVoxelStore.open(FabricLoader.getInstance().getGameDir().resolve("sofronpack-cache")
                    .resolve("multiplayer").resolve(key.toString()), dimension);
            if (ExtendedVoxelStore.meshCacheDirectory() == null) return;
            active = level;
            // The option may have been enabled after the chunk packets arrived.
            int radius = client.options.getEffectiveRenderDistance() + 3;
            var center = client.player.chunkPosition();
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(center.x() + x, center.z() + z,
                            ChunkStatus.FULL, false);
                    if (chunk != null) pending.add(chunk);
                }
            }
        }
        if ((level.getGameTime() % 40) == 0) ExtendedVoxelStore.flush();
        if (!pending.isEmpty() && copies.availablePermits() > 0) submit(pending.removeFirst());
    }

    private static void submit(LevelChunk chunk) {
        copies.acquireUninterruptibly();
        try {
            var snapshot = ExtendedVoxelStore.captureSections(active, chunk);
            encoder.execute(() -> {
                try {
                    ExtendedVoxelStore.put(snapshot.get());
                } finally {
                    copies.release();
                }
            });
        } catch (RuntimeException | Error failure) {
            copies.release();
            throw failure;
        }
    }

    private static void close() {
        if (active == null) return;
        while (!pending.isEmpty()) submit(pending.removeFirst());
        copies.acquireUninterruptibly(MAX_COPIES);
        try {
            ExtendedVoxelStore.close();
            active = null;
        } finally {
            copies.release(MAX_COPIES);
        }
    }
}
