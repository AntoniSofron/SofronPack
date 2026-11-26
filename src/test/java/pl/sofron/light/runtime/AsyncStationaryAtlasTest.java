package pl.sofron.light.runtime;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncStationaryAtlasTest {
    @Test void dirtyPagesCoalesceAndSkipUnchanged() {
        ByteBuffer a=ByteBuffer.allocate(20000), b=ByteBuffer.allocate(20000);
        assertTrue(AsyncStationaryAtlas.changes(a,b).isEmpty());
        b.put(4097,(byte)1); b.put(8193,(byte)2); b.put(19999,(byte)3);
        assertEquals(List.of(new AsyncStationaryAtlas.Range(4096,8192),
                new AsyncStationaryAtlas.Range(16384,3616)),AsyncStationaryAtlas.changes(a,b));
    }
    @Test void growthAndShrink() {
        assertEquals(List.of(new AsyncStationaryAtlas.Range(4096,904)),
                AsyncStationaryAtlas.changes(ByteBuffer.allocate(4096), ByteBuffer.allocate(5000)));
        assertTrue(AsyncStationaryAtlas.changes(ByteBuffer.allocate(5000),ByteBuffer.allocate(4096)).isEmpty());
    }
    @Test void immutableBucketSnapshot() {
        var original=new ChunkLightBuckets();
        var source=new pl.sofron.light.engine.LightSource(0.5,64.5,0.5,6f,1f,
                new pl.sofron.light.engine.RgbLight(1,.4f,.1f));
        original.replace(0,0,List.of(source));
        var snapshot=original.snapshot();
        original.remove(0,0);
        assertEquals(List.of(source),snapshot.affecting(0,0));
        assertTrue(original.affecting(0,0).isEmpty());
    }
}