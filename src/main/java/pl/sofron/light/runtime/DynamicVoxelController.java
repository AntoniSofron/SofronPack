package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import pl.sofron.light.config.LightConfig;

public final class DynamicVoxelController {
    private final DynamicEmitterRegistry registry=new DynamicEmitterRegistry();
    private DynamicVoxelField.Snapshot field;
    private List<DynamicVoxelField.Emitter> previous = List.of();
    private int age=0, collisionAge=0;
    private Object world;
    private final DynamicOcclusionCache collision=new DynamicOcclusionCache();
    private boolean environmentDirty = true;
    public DynamicVoxelField.Snapshot field(){return field;}

    public void invalidate(net.minecraft.core.BlockPos pos) {
        collision.invalidate(pos.getX(),pos.getY(),pos.getZ());
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
        collision.invalidateChunk(cx,cz);
        var current = field;
        if (current == null) return;
        int x0 = cx << 4, z0 = cz << 4;
        if (x0 <= current.x0() + DynamicVoxelField.SIZE &&
                x0 + 15 >= current.x0() - 1 &&
                z0 <= current.z0() + DynamicVoxelField.SIZE &&
                z0 + 15 >= current.z0() - 1) environmentDirty = true;
    }
    public DynamicEmitterRegistry registry(){return registry;}
    public void clear(){field=null;previous=List.of();age=0;collisionAge=0;environmentDirty=true;registry.clear();collision.clear();collision.source(pl.sofron.light.engine.LightOcclusion.NONE);world=null;}
    public void tick(Minecraft client,LightConfig config){
        tick(client, config, client.level == null ? List.of() : client.level.entitiesForRendering());
    }

    public void tick(Minecraft client,LightConfig config, Iterable<net.minecraft.world.entity.Entity> nearbyEntities){
        if(client.level==null || client.player==null || !config.dynamicLighting){clear();return;}
        if(world!=client.level){clear();world=client.level;collision.source(new MinecraftOcclusion(client.level));}
        // Refresh independently of camera movement; the old age counter was reset on every move.
        if(++collisionAge>=40){collisionAge=0;collision.clear();environmentDirty=true;}
        registry.update(client,config,nearbyEntities);
        int x=client.player.blockPosition().getX(), y=client.player.blockPosition().getY()+1,
            z=client.player.blockPosition().getZ();
        if (registry.sources().isEmpty()) { if(field!=null)collision.clear();field=null; previous=List.of(); age=0; environmentDirty=true; return; }
        List<DynamicVoxelField.Emitter> emitters=new ArrayList<>(registry.sources().size());
        for(var s:registry.sources()){
            emitters.add(new DynamicVoxelField.Emitter((int)Math.floor(s.x()),(int)Math.floor(s.y()),
               (int)Math.floor(s.z()),s.vanillaLevel(),s.red(),s.green(),s.blue(),s.tintRadius()));
        }
        // Max-channel composition is order independent; stable comparison avoids
        // rebuilding when two equally distant sources swap sorting order.
        emitters.sort(java.util.Comparator.comparingInt(DynamicVoxelField.Emitter::x)
            .thenComparingInt(DynamicVoxelField.Emitter::y).thenComparingInt(DynamicVoxelField.Emitter::z)
            .thenComparingInt(DynamicVoxelField.Emitter::level).thenComparingInt(DynamicVoxelField.Emitter::red)
            .thenComparingInt(DynamicVoxelField.Emitter::green).thenComparingInt(DynamicVoxelField.Emitter::blue)
            .thenComparingInt(DynamicVoxelField.Emitter::tintRange));
        int half = DynamicVoxelField.SIZE / 2;
        if (field != null && field.x0() == x-half && field.y0() == y-half && field.z0() == z-half
                && previous.equals(emitters) && !environmentDirty && ++age < 40) return;
        age=0;
        environmentDirty=false;
        previous=emitters;
        collision.retain(x-half,y-half,z-half,DynamicVoxelField.SIZE);
        var next=DynamicVoxelField.propagate(x,y,z,emitters,collision);
        if (field == null || field.x0()!=next.x0() || field.y0()!=next.y0() || field.z0()!=next.z0()
                || !java.util.Arrays.equals(field.voxels(), next.voxels())) field=next;
    }
}
