package pl.sofron.light.runtime;

import org.junit.jupiter.api.Test;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class ChunkLightBucketsTest {
    private static LightSource light(double x,double z,float radius) {
        return new LightSource(x,64,z,radius,1f,new RgbLight(1f,0.4f,0.2f));
    }

    @Test
    void onlyNearbyCachesAreInvalidated() {
        ChunkLightBuckets buckets = new ChunkLightBuckets();
        buckets.replace(0,0,List.of(light(15,8,6)));
        List<LightSource> adjacent = buckets.affecting(1,0);
        List<LightSource> distant = buckets.affecting(10,10);
        assertEquals(1,adjacent.size());
        assertTrue(distant.isEmpty());
        assertSame(adjacent,buckets.affecting(1,0));
        assertSame(distant,buckets.affecting(10,10));

        // Keep the same maximal radius so unrelated target columns retain
        // their memoized result even when a light source moves.
        buckets.replace(0,0,List.of(light(1,8,6)));
        assertNotSame(adjacent,buckets.affecting(1,0));
        assertTrue(buckets.affecting(1,0).isEmpty());
        assertSame(distant,buckets.affecting(10,10));

        buckets.remove(0,0);
        assertTrue(buckets.affecting(0,0).isEmpty());
        assertEquals(0,buckets.chunkCount());
    }

    @Test
    void cacheIsBoundedDuringLongExploration() {
        ChunkLightBuckets buckets = new ChunkLightBuckets();
        for (int i=0;i<20000;i++) buckets.affecting(i,10);
        assertEquals(0,buckets.chunkCount());
        assertEquals(17000,buckets.cachedColumns());
        assertTrue(buckets.affecting(10000,10).isEmpty());
        // The query cache itself remains bounded even after visiting 20000
        // empty columns. The unit test only exercises its public semantics.
        assertTrue(buckets.affecting(-1,-1).isEmpty());
    }

    @Test
    void changesInMaxRadiusInvalidateAllReachDistances() {
        ChunkLightBuckets buckets = new ChunkLightBuckets();
        buckets.replace(0,0,List.of(light(8,8,3)));
        assertTrue(buckets.affecting(2,0).isEmpty());
        buckets.replace(0,0,List.of(light(8,8,33)));
        assertEquals(1,buckets.affecting(2,0).size());
        assertEquals(1,buckets.sourceCount());
        buckets.clear();
        assertTrue(buckets.affecting(2,0).isEmpty());
    }
}
