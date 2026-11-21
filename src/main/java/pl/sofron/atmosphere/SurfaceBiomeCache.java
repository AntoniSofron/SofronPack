package pl.sofron.atmosphere;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cache of surface quart-column biome IDs. Heightmap and noise biome lookups
 * are considerably more expensive than a map read on every camera update. */
final class SurfaceBiomeCache {
    @FunctionalInterface
    interface Resolver {
        String resolve(int quartX, int quartZ);
    }

    private final Map<Long, String> columns;
    private final long refreshTicks;
    private Object world;
    private long refreshed = Long.MIN_VALUE;

    SurfaceBiomeCache(int maxColumns, long refreshTicks) {
        if (maxColumns < 1 || refreshTicks < 1) throw new IllegalArgumentException();
        this.refreshTicks = refreshTicks;
        columns = new LinkedHashMap<>(Math.min(1024, maxColumns), 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
                return size() > maxColumns;
            }
        };
    }

    boolean prepare(Object currentWorld, long time) {
        if (world != currentWorld || time < refreshed || time - refreshed >= refreshTicks) {
            columns.clear();
            world = currentWorld;
            refreshed = time;
            return true;
        }
        return false;
    }

    String get(int quartX, int quartZ, Resolver source) {
        long key = ((long) quartX << 32) | (quartZ & 0xffffffffL);
        String found = columns.get(key);
        if (found != null) return found;
        String resolved = source.resolve(quartX, quartZ);
        columns.put(key, resolved);
        return resolved;
    }

    int size() { return columns.size(); }
}
