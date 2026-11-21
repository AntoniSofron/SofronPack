package pl.sofron.extended;

import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** Transient horizon while decorated chunk generation catches up. Never enters the saved voxel cache. */
public final class ExtendedTerrainPreview {
    public static final String[] MATERIALS = {"minecraft:air", "minecraft:grass_block",
            "minecraft:sand", "minecraft:snow_block", "minecraft:water"};
    private static final int RADIUS = ExtendedLodLevels.FAR / ExtendedVoxelMesher.TILE_CHUNKS + 1;
    private static final long[] ORDER = ExtendedWorldPrefetch.spiralOffsets(RADIUS);
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "SofronPack-Horizon");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static volatile Session active;
    private ExtendedTerrainPreview() {}

    private record Sample(int ground, int material, int grass, int water) {}
    public static final class Tile {
        private final Sample[] samples;
        public final int minimum, sea;
        Tile(Sample[] samples, int minimum, int sea) {
            this.samples = samples; this.minimum = minimum; this.sea = sea;
        }

        public int ground(int x, int z) {
            int i = (z >> 5) * 3 + (x >> 5);
            int dx = x & 31, dz = z & 31;
            int north = samples[i].ground * (32 - dx) + samples[i + 1].ground * dx;
            int south = samples[i + 3].ground * (32 - dx) + samples[i + 4].ground * dx;
            return (north * (32 - dz) + south * dz + 512) >> 10;
        }

        private Sample nearest(int x, int z) { return samples[((z + 16) >> 5) * 3 + ((x + 16) >> 5)]; }
        int material(int x, int z, int ground) { return ground < sea ? 4 : nearest(x, z).material; }
        int grass(int x, int z) { return nearest(x, z).grass; }
        int water(int x, int z) { return nearest(x, z).water; }
    }

    private static final class Session {
        final ServerLevel level;
        final ChunkGenerator generator;
        final RandomState random;
        final LevelHeightAccessor heights;
        final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> changes = new ConcurrentLinkedQueue<>();
        final LinkedHashMap<Long, Sample> samples = new LinkedHashMap<>(2048, .75f, true);
        BiomeResolver biomes;
        volatile long center = Long.MIN_VALUE;
        volatile boolean running;
        Session(ServerLevel level) {
            this.level = level;
            generator = level.getChunkSource().getGenerator();
            random = level.getChunkSource().randomState();
            heights = LevelHeightAccessor.create(level.getMinY(), level.getHeight());
        }

        Sample sample(int gx, int gz) {
            long key = ExtendedVoxelStore.pack(gx, gz);
            Sample result = samples.get(key);
            if (result != null) return result;
            int x = gx * 32, z = gz * 32;
            int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random);
            var biome = biomes.getNoiseBiome(x >> 2, h >> 2, z >> 2).value();
            float temp = biome.getBaseTemperature();
            int material = temp < .15f ? 3 : temp >= 1.5f || Math.abs(h - generator.getSeaLevel()) <= 1 ? 2 : 1;
            result = new Sample(h, material, biome.getGrassColor(x, z), biome.getWaterColor());
            samples.put(key, result);
            if (samples.size() > 24000) samples.pollFirstEntry();
            return result;
        }

        void generate() {
            try {
                if (biomes == null) biomes = generator.getBiomeSource().createCachingResolver(random);
                long target;
                do {
                    target = center;
                    int cx = (int)(target >> 32), cz = (int)target;
                    tiles.keySet().removeIf(k -> Math.abs((int)(k >> 32) - cx) > RADIUS + 2
                            || Math.abs((int)(long)k - cz) > RADIUS + 2);
                    for (long offset : ORDER) {
                        if (active != this || target != center) break;
                        int tx = cx + (int)(offset >> 32), tz = cz + (int)offset;
                        long key = ExtendedVoxelStore.pack(tx, tz);
                        if (tiles.containsKey(key)) continue;
                        var nodes = new Sample[9];
                        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++)
                            nodes[z * 3 + x] = sample(tx * 2 + x, tz * 2 + z);
                        tiles.put(key, new Tile(nodes, heights.getMinY(), generator.getSeaLevel()));
                        changes.add(key);
                    }
                } while (active == this && target != center);
            } catch (RuntimeException error) {
                System.err.println("[SofronPack] Background horizon sampling: " + error.getMessage());
            } finally { running = false; }
        }
    }

    public static void move(ServerLevel level, int cx, int cz) {
        if (level.dimension() != Level.OVERWORLD ||
                !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
            clear(); return;
        }
        Session s = active;
        if (s == null || s.level != level) active = s = new Session(level);
        long center = ExtendedVoxelStore.pack(Math.floorDiv(cx, 4), Math.floorDiv(cz, 4));
        if (s.center == center && (s.running || s.tiles.containsKey(center))) return;
        s.center = center;
        if (!s.running) {
            s.running = true;
            WORK.execute(s::generate);
        }
    }

    public static Tile get(int chunkX, int chunkZ) {
        Session s = active;
        return s == null ? null : s.tiles.get(ExtendedVoxelStore.pack(Math.floorDiv(chunkX, 4), Math.floorDiv(chunkZ, 4)));
    }
    public static Long pollChanged() { Session s = active; return s == null ? null : s.changes.poll(); }
    public static void clear() { active = null; }
}
