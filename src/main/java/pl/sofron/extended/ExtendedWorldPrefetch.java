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
 * compressed 3D block-material LOD snapshot and relinquishes the chunk immediately.
 */
public final class ExtendedWorldPrefetch {
    private static final int RADIUS = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final long[] OFFSETS = spiralOffsets(RADIUS);
    private static final int MAX_PENDING = 24;
    private static int ticks, cursor, nearbyCursor;
    private static int pending;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedVoxelStore.close();
            pending = 0;
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
            pending = 0;
        }
        ExtendedVoxelStore.open(server.getWorldPath(LevelResource.ROOT),
                level.dimension().identifier().toString());
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        if (cx != centerX || cz != centerZ) {
            // Do not restart the expensive 128-chunk scan every time the
            // player crosses a chunk boundary. Continuous flight otherwise
            // prevents the cursor from ever reaching the distant rings.
            if (centerX != Integer.MIN_VALUE &&
                    Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) > 24) {
                cursor = 0; // Actual teleport / dimension repositioning.
            }
            centerX = cx;
            centerZ = cz;
        }

        // Nearby live data can change through normal gameplay. Refresh only
        // loaded chunks at a bounded rate; frozen far LOD is not resampled.
        int normal = Math.max(2, server.getPlayerList().getViewDistance());
        if (ticks % 10 == 0) refreshNearby(level, cx, cz, normal);

        if ((++ticks % 40) == 0) ExtendedVoxelStore.flush();
        // Allow more asynchronous FULL chunk requests while the integrated
        // server has spare time. This improves streaming throughput but cannot
        // make the game's underlying world generator 100x faster.
        // Throttle hard as soon as server tick duration becomes critical.
        boolean busyServer = server.getAverageTickTimeNanos() > 35_000_000L;
        if (busyServer && ticks % 4 != 0) return;
        int submitBudget = busyServer ? 1 : 6;
        for (int submitted = 0; submitted < submitBudget && pending < MAX_PENDING; ) {
            int inspected = 0;
            boolean launched = false;
            while (inspected++ < 2048) {
                if (cursor == OFFSETS.length) cursor = 0;
                long offset = OFFSETS[cursor++];
                int dx = (int)(offset >> 32), dz = (int)offset;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= normal) continue;
                int x = cx + dx, z = cz + dz;
                if (ExtendedVoxelStore.has(x, z)) continue;

                final ServerLevel expectedLevel = level;
                pending++;
                submitted++;
                launched = true;
                try {
                    var future = ((ExtendedChunkFutureAccess)level.getChunkSource())
                            .sofron$getChunkFuture(x, z, ChunkStatus.FULL, true);
                    future.whenComplete((result, error) -> server.execute(() -> {
                        try {
                            if (expectedLevel != lastLevel || error != null || result == null) return;
                            result.ifSuccess(chunk -> {
                                if (chunk instanceof LevelChunk full) {
                                    ExtendedVoxelStore.put(ExtendedVoxelStore.sample(expectedLevel, full));
                                }
                            });
                        } finally {
                            if (expectedLevel == lastLevel) pending = Math.max(0, pending - 1);
                        }
                    }));
                } catch (RuntimeException failure) {
                    pending = Math.max(0, pending - 1);
                    System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
                }
                break;
            }
            if (!launched) break;
        }
    }

    private static void refreshNearby(ServerLevel level, int cx, int cz, int normal) {
        int size = normal * 2 + 1;
        int total = size * size;
        for (int i = 0; i < 1; i++) {
            int slot = nearbyCursor++ % total;
            int dx = slot % size - normal, dz = slot / size - normal;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
            if (chunk != null) {
                ExtendedVoxelStore.put(ExtendedVoxelStore.sample(level, chunk));
            }
        }
    }

    static long pack(int x, int z) { return ExtendedVoxelStore.pack(x, z); }

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
