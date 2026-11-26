package pl.sofron.light.runtime;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;
import static org.junit.jupiter.api.Assertions.*;

class StationaryReachTest {
    @Test void wallBlocksAndGapPasses(){
        var source=new LightSource(4.5,8.5,8.5,12,1,new RgbLight(1,0,0));
        var reach=new StationaryReach();
        int node=(4*9+4)*9+6;
        assertFalse(reach.sample(List.of(source),0,0,0,(x,y,z)->x==8)[node]);
        assertTrue(reach.sample(List.of(source),0,0,0,(x,y,z)->x==8&&!(y==8&&z==8))[node]);
        assertTrue(reach.sample(List.of(source),0,0,0,(x,y,z)->false)[node]);
    }
    @Test void immutableMaskSnapshot(){
        var geometry=new StationaryOcclusion();
        long[] bits=new long[64];StationaryOcclusion.set(bits,3,4,5);
        geometry.replace(0,0,new StationaryOcclusion.Column(0,new long[][]{bits}));
        var copy=geometry.snapshot();
        geometry.replace(0,0,new StationaryOcclusion.Column(0,new long[][]{null}));
        assertTrue(copy.blocks(3,4,5));assertFalse(geometry.blocks(3,4,5));
        assertFalse(copy.blocks(4,4,5));assertTrue(copy.blocks(20,4,5));
    }
}