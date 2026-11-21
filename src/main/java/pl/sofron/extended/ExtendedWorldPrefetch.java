package pl.sofron.extended;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import pl.sofron.extended.mixin.ExtendedChunkFutureAccess;

/**
 * The only producer of previously unseen distant terrain. Minecraft generates
 * real chunks once through its own asynchronous pipeline; SofronPack stores a
 * small immutable LOD surface and relinquishes the chunk immediately.
 */
public final class ExtendedWorldPrefetch {
    private static final int RADIUS = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final long[] OFFSETS = spiralOffsets(RADIUS);
    private static int ticks, cursor, nearbyCursor;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static boolean pending;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedWorldStorage.close();
            pending = false;
            lastLevel = null;
            cursor = nearbyCursor = ticks = 0;
            centerX = centerZ = Integer.MIN_VALUE;
        });
    }

    private static void tick(MinecraftServer server) {
        // The integrated server shares the database with its own client.
        // Never generate chunks without authority on an external server.
        if (!server.isSingleplayer() || !ExtendedWorldConfig.get().enabled) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();

        if (level != lastLevel) {
            lastLevel = level;
            centerX = centerZ = Integer.MIN_VALUE;
            cursor = nearbyCursor = 0;
            pending = false;
        }
        ExtendedWorldStorage.open(server.getWorldPath(LevelResource.ROOT),
                level.dimension().identifier().toString());
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        if (cx != centerX || cz != centerZ) {
            centerX = cx; centerZ = cz; cursor = 0;
        }

        // Nearby live data can change through normal gameplay. Refresh only
        // loaded chunks at a bounded rate; frozen far LOD is not resampled.
        int normal = Math.max(2, server.getPlayerList().getViewDistance());
        refreshNearby(level, cx, cz, normal);

        if ((++ticks % 40) == 0) ExtendedWorldStorage.flush();
        if (pending || ticks % 4 != 0) return;
        int inspected = 0;
        while (inspected++ < 1024) {
            if (cursor == OFFSETS.length) cursor = 0;
            long offset = OFFSETS[cursor++];
            int dx = (int)(offset >> 32), dz = (int)offset;
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= normal) continue;
            int x = cx + dx, z = cz + dz;
            if (ExtendedWorldStorage.has(x, z)) continue;
            pending = true;
            final ServerLevel expectedLevel = level;
            try {
                var future = ((ExtendedChunkFutureAccess) level.getChunkSource())
                        .sofron$getChunkFuture(x, z, ChunkStatus.FULL, true);
                future.whenComplete((result, error) -> server.execute(() -> {
                    try {
                        if (expectedLevel != lastLevel || error != null || result == null) return;
                        result.ifSuccess(chunk -> {
                            if (chunk instanceof LevelChunk full) {
                                ExtendedWorldStorage.put(ExtendedWorldStorage.sample(expectedLevel, full));
                            }
                        });
                    } finally {
                        pending = false;
                    }
                }));
            } catch (RuntimeException failure) {
                pending = false;
                System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
            }
            return;
        }
    }

    private static void refreshNearby(ServerLevel level, int cx, int cz, int normal) {
        int size = normal * 2 + 1;
        int total = size * size;
        for (int i = 0; i < 2; i++) {
            int slot = nearbyCursor++ % total;
            int dx = slot % size - normal, dz = slot / size - normal;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
            if (chunk != null) {
                ExtendedWorldStorage.put(ExtendedWorldStorage.sample(level, chunk));
            }
        }
    }

    static long pack(int x, int z) { return ExtendedWorldStorage.pack(x, z); }

    static long[] spiralOffsets(int radius) {
        if (radius < 0 || radius > 256) throw new IllegalArgumentException("radius");
        int side = radius * 2 + 1;
        long[] result = new long[side * side];
        int i = 0;
        result[i++] = pack(0, 0);
        for (int ring = 1; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        result[i++] = pack(dx, dz);
                    }
                }
            }
        }
        if (i != result.length) throw new IllegalStateException("Invalid LOD ring traversal");
        return result;
    }
}
