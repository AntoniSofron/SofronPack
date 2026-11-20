package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import pl.sofron.light.config.LightConfig;

public final class DynamicVoxelController {
    private final DynamicEmitterRegistry registry=new DynamicEmitterRegistry();
    private DynamicVoxelField.Snapshot field;
    private List<DynamicVoxelField.Emitter> previous = List.of();
    private int age=0;
    private boolean environmentDirty = true;
    public DynamicVoxelField.Snapshot field(){return field;}

    public void invalidate(net.minecraft.core.BlockPos pos) {
        var current = field;
        if (current == null) return;
        int x = pos.getX() - current.x0();
        int y = pos.getY() - current.y0();
        int z = pos.getZ() - current.z0();
        if (x >= -1 && y >= -1 && z >= -1 &&
                x <= DynamicVoxelField.SIZE && y <= DynamicVoxelField.SIZE &&
                z <= DynamicVoxelField.SIZE) environmentDirty = true;
    }

    public void invalidateChunk(int cx, int cz) {
        var current = field;
        if (current == null) return;
        int x0 = cx << 4, z0 = cz << 4;
        if (x0 <= current.x0() + DynamicVoxelField.SIZE &&
                x0 + 15 >= current.x0() - 1 &&
                z0 <= current.z0() + DynamicVoxelField.SIZE &&
                z0 + 15 >= current.z0() - 1) environmentDirty = true;
    }
    public DynamicEmitterRegistry registry(){return registry;}
    public void clear(){field=null;previous=List.of();age=0;environmentDirty=true;registry.clear();}
    public void tick(Minecraft client,LightConfig config){
        if(client.level==null || client.player==null || !config.dynamicLighting){clear();return;}
        registry.update(client,config);
        int x=client.player.blockPosition().getX(), y=client.player.blockPosition().getY()+1,
            z=client.player.blockPosition().getZ();
        if (registry.sources().isEmpty()) { field=null; previous=List.of(); age=0; environmentDirty=true; return; }
        List<DynamicVoxelField.Emitter> emitters=new ArrayList<>();
        for(var s:registry.sources()){
            emitters.add(new DynamicVoxelField.Emitter((int)Math.floor(s.x()),(int)Math.floor(s.y()),
               (int)Math.floor(s.z()),s.vanillaLevel(),s.red(),s.green(),s.blue(),s.tintRadius()));
        }
        int half = DynamicVoxelField.SIZE / 2;
        if (field != null && field.x0() == x-half && field.y0() == y-half && field.z0() == z-half
                && previous.equals(emitters) && !environmentDirty && ++age < 40) return;
        age=0;
        environmentDirty=false;
        previous=emitters;
        var next=DynamicVoxelField.propagate(x,y,z,emitters,new MinecraftOcclusion(client.level));
        if (field == null || field.x0()!=next.x0() || field.y0()!=next.y0() || field.z0()!=next.z0()
                || !java.util.Arrays.equals(field.voxels(), next.voxels())) field=next;
    }
}
