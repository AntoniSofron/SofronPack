package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightOptimizationTest {
    @Test void groupedEqualsIndependentMax(){
        var rng=new Random(42);
        var emitters=new ArrayList<DynamicVoxelField.Emitter>();
        for(int i=0;i<40;i++)emitters.add(new DynamicVoxelField.Emitter(rng.nextInt(25)-12,
                rng.nextInt(9)-4,rng.nextInt(25)-12,i%3==0?10:14,255,i%3==0?60:120,25,7));
        pl.sofron.light.engine.LightOcclusion world=(x,y,z)->x==0&&z!=2 || (x*17+y*31+z*13)%43==0;
        int[] expected=new int[DynamicVoxelField.CELLS];
        for(var e:emitters){
            int[] one=DynamicVoxelField.propagate(0,0,0,List.of(e),world).voxels();
            for(int i=0;i<one.length;i++){
                int a=expected[i],b=one[i];
                expected[i]=Math.max(a&0xff000000,b&0xff000000)|Math.max(a&0xff0000,b&0xff0000)
                    |Math.max(a&0xff00,b&0xff00)|Math.max(a&255,b&255);
            }
        }
        assertArrayEquals(expected,DynamicVoxelField.propagate(0,0,0,emitters,world).voxels());
    }
    @Test void collisionCacheRetainsOverlapAndInvalidates(){
        var calls=new AtomicInteger();
        var cache=new DynamicOcclusionCache();
        cache.source((x,y,z)->{calls.incrementAndGet();return x==0;});
        assertTrue(cache.blocks(0,64,-1));
        assertTrue(cache.blocks(0,64,-1));
        assertEquals(1,calls.get());
        cache.retain(-16,48,-16,33);
        assertTrue(cache.blocks(0,64,-1));assertEquals(1,calls.get());
        cache.invalidate(0,64,0);
        assertTrue(cache.blocks(0,64,-1));assertEquals(2,calls.get());
        cache.retain(100,48,100,33);
        assertTrue(cache.blocks(0,64,-1));assertEquals(3,calls.get());
    }
    @Test void staticTargetEarlyExitMatchesFull(){
        var source=new pl.sofron.light.engine.LightSource(4.5,8.5,8.5,12,1,
                new pl.sofron.light.engine.RgbLight(1,0,0));
        var reach=new StationaryReach();
        var full=reach.sample(List.of(source),0,0,0,(x,y,z)->x==8&&y!=8).clone();
        float[] weights=new float[729];
        for(int i=0;i<weights.length;i+=13)weights[i]=1;
        var selected=reach.sample(List.of(source),0,0,0,(x,y,z)->x==8&&y!=8,weights);
        for(int i=0;i<729;i++)assertEquals(weights[i]>0&&full[i],selected[i]);
    }
}