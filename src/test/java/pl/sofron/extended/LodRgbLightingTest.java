package pl.sofron.extended;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.sofron.light.engine.BlockLightRules;
import pl.sofron.light.engine.RgbLight;

class LodRgbLightingTest{
    @Test void settingsReach(){
        var rule=new BlockLightRules.Rule(new RgbLight(1,0,0),1,null,64f);
        assertEquals(4,new LodRgbLighting.Settings(true,10,Map.of("minecraft:torch",rule)).halo());
        assertEquals(1,new LodRgbLighting.Settings(false,10,Map.of("minecraft:torch",rule)).halo());
    }
    @Test void bakedSourceColorsSavedTerrain(){
        var rule=new BlockLightRules.Rule(new RgbLight(1,0,0),1,null,6f);
        short[] cells=new short[4096];
        cells[(8*16+8)*16+8]=1;
        var chunk=ExtendedVoxelStore.encode(0,0,0,16,new String[]{"minecraft:air","minecraft:torch"},cells);
        ExtendedVoxelMesher.MaterialResolver materials=(id,dir)->new ExtendedVoxelMesher.MaterialUV(0,0,1,1,-1,true,14);
        var light=LodRgbLighting.build(new ExtendedVoxelStore.VoxelChunk[]{chunk},materials,
                new LodRgbLighting.Settings(true,10,Map.of("minecraft:torch",rule)),0,0);
        assertNotNull(light);
        int rgb=light.packed(8,8,8);
        assertTrue((rgb>>>25)>0);
        assertEquals(255,(rgb>>>1)&255);
        assertEquals(0,(rgb>>>9)&255);
        assertEquals(0,light.packed(31,8,31));
    }
}