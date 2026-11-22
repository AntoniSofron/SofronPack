package pl.sofron.optimization;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.sofron.optimization.chunk.SofronOreBitsetCache;

class SofronOreBitsetCacheTest {
    @Test void clearsBeforeEachReuseAndLimitsHugeRetention() {
        BitSet first = SofronOreBitsetCache.empty(512);
        first.set(1);
        first.set(400);
        BitSet second = SofronOreBitsetCache.empty(512);
        assertSame(first, second);
        assertTrue(second.isEmpty());
        BitSet large = SofronOreBitsetCache.empty(100000);
        assertNotSame(first, large);
        assertSame(first, SofronOreBitsetCache.empty(1));
        assertThrows(IllegalArgumentException.class, () -> SofronOreBitsetCache.empty(-1));
    }

    @Test void doesNotShareMutableBitsetAcrossWorldgenThreads() throws InterruptedException {
        var workerBitset = new AtomicReference<BitSet>();
        Thread worker = new Thread(() -> workerBitset.set(SofronOreBitsetCache.empty(64)));
        worker.start();
        worker.join();
        assertNotSame(SofronOreBitsetCache.empty(64), workerBitset.get());
    }
}
