package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceBiomeCacheTest {
    @Test
    void repeatedPositionsDoNotQueryWorldAgain() {
        SurfaceBiomeCache cache = new SurfaceBiomeCache(1024, 200);
        AtomicInteger calls = new AtomicInteger();
        SurfaceBiomeCache.Resolver resolver = (x,z) -> {
            calls.incrementAndGet();
            return x < 3 ? "minecraft:plains" : "minecraft:desert";
        };
        Object world = new Object();
        cache.prepare(world, 20);
        for (int repeat=0; repeat<100; repeat++) {
            for (int dz=-2; dz<4; dz++) for (int dx=-2; dx<4; dx++) {
                assertNotNull(cache.get(dx,dz,resolver));
            }
        }
        assertEquals(36,calls.get(), "3600 samples should do only 36 biome queries");
        cache.prepare(world, 30);
        cache.get(0,0,resolver);
        assertEquals(36,calls.get());
        cache.prepare(world, 220);
        cache.get(0,0,resolver);
        assertEquals(37,calls.get(), "Periodic refresh detects modified biomes");
        cache.prepare(new Object(),221);
        cache.get(0,0,resolver);
        assertEquals(38,calls.get(), "Switching worlds invalidates the cache");
    }

    @Test
    void boundsMemory() {
        SurfaceBiomeCache cache = new SurfaceBiomeCache(16, 200);
        cache.prepare(new Object(), 0);
        for(int x=0;x<200;x++) cache.get(x,-x,(a,b)->"minecraft:desert");
        assertEquals(16,cache.size());
    }
}
