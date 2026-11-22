package pl.sofron.extended;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodVertexSpaceTest {
    @Test void rebasingPreservesExactHeightUvsTintAndEmission() {
        var vertices = ByteBuffer.allocateDirect(4 * 24).order(ByteOrder.nativeOrder());
        for(int i=0;i<4;i++) vertices.putFloat(i).putFloat(-63.25f)
                .putFloat(i+2.5f).putFloat(.123f).putFloat(.456f).putInt(0xAABBCCDD);
        vertices.flip();
        ExtendedLodVertexSpace.rebase(vertices,-1024,2048,-1088,1984);
        assertEquals(0,vertices.position());
        for(int i=0;i<4;i++){
            int p=i*24;
            assertEquals(i+64f,vertices.getFloat(p));
            assertEquals(-63.25f,vertices.getFloat(p+4));
            assertEquals(i+66.5f,vertices.getFloat(p+8));
            assertEquals(.123f,vertices.getFloat(p+12));
            assertEquals(.456f,vertices.getFloat(p+16));
            assertEquals(0xAABBCCDD,vertices.getInt(p+20));
        }
    }

    @Test void recentersOnlyAfterLargeTravelToRetainFloatPrecision(){
        assertTrue(ExtendedLodVertexSpace.needsRebase(Long.MIN_VALUE,Long.MIN_VALUE,0,0));
        assertFalse(ExtendedLodVertexSpace.needsRebase(0,0,8191,-8191));
        assertTrue(ExtendedLodVertexSpace.needsRebase(0,0,8192,0));
        assertTrue(ExtendedLodVertexSpace.needsRebase(0,0,0,-8192));
        assertFalse(ExtendedLodVertexSpace.needsRebase(-16000000,16000000,-16000007,16000003));
    }
}
