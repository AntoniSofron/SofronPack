package pl.sofron.extended;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Direction;
import pl.sofron.light.engine.BlockLightRules;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.runtime.ChunkLightAtlas;
import pl.sofron.light.runtime.ChunkLightBuckets;
import pl.sofron.light.runtime.StationaryOcclusion;

/** Static RGB from saved voxel columns, including columns outside live render distance.
 * Baked into existing spare quad bits on the LOD worker; no extra GPU buffer/pass. */
public final class LodRgbLighting {
    public record Settings(boolean enabled,float radius,Map<String,BlockLightRules.Rule> rules){
        public int halo(){
            if(!enabled)return 1;
            float max=radius*.68f;
            for(var rule:rules.values())if(rule.rgbRadius()!=null)max=Math.max(max,rule.rgbRadius());
            return Math.max(1,(int)Math.ceil(max/16.0));
        }
        public String fingerprint(){return enabled+":"+radius+":"+new TreeMap<>(rules);}
        BlockLightRules.Rule rule(String id){
            int p=id.indexOf('[');if(p>=0)id=id.substring(0,p);
            var r=rules.get(id);
            return r!=null?r:rules.get(BlockLightRules.canonicalTorchId(id));
        }
    }
    private final IntBuffer data;
    private final int ox,oz,width;
    private LodRgbLighting(IntBuffer data,int ox,int oz,int width){
        this.data=data;this.ox=ox;this.oz=oz;this.width=width;
    }
    public static LodRgbLighting build(ExtendedVoxelStore.VoxelChunk[] chunks,
            ExtendedVoxelMesher.MaterialResolver materials,Settings settings,int tx,int tz){
        if(!settings.enabled())return null;
        boolean any=false;
        for(var c:chunks){
            if(c==null)continue;
            for(String id:c.palette())if(settings.rule(id)!=null){any=true;break;}
            if(any)break;
        }
        if(!any)return null;
        var index=new ChunkLightBuckets();
        for(var c:chunks){
            if(c==null)continue;
            String[] palette=c.palette();
            boolean[] solid=new boolean[palette.length];
            float[] radius=new float[palette.length];
            BlockLightRules.Rule[] rules=new BlockLightRules.Rule[palette.length];
            for(int i=1;i<palette.length;i++){
                var uv=materials.face(palette[i],Direction.UP);
                if(uv==null)continue;
                solid[i]=!uv.translucent()&&materials.model(palette[i])==null;
                var r=settings.rule(palette[i]);
                if(r!=null&&r.intensity()>0&&uv.emission()>0){
                    int level=r.emission()==null?uv.emission():r.emission();
                    if(level>0){radius[i]=BlockLightRules.tintRadius(r,settings.radius(),level);rules[i]=r;}
                }
            }
            short[] cells=c.unpack();
            long[][] mask=new long[(c.yCount()+15)/16][];
            int x0=c.chunkX()*16,z0=c.chunkZ()*16;
            var sources=new ArrayList<LightSource>();
            for(int y=0;y<c.yCount();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int id=cells[(y*16+z)*16+x]&65535;
                if(solid[id]){
                    if(mask[y>>4]==null)mask[y>>4]=new long[64];
                    StationaryOcclusion.set(mask[y>>4],x,y,z);
                }
                if(radius[id]>0){
                    var rule=rules[id];
                    sources.add(new LightSource(x0+x+.5,c.minY()+y+.5,z0+z+.5,
                            radius[id],rule.intensity(),rule.color()));
                }
            }
            index.geometry().replace(c.chunkX(),c.chunkZ(),new StationaryOcclusion.Column(c.minY()>>4,mask));
            if(!sources.isEmpty())index.replace(c.chunkX(),c.chunkZ(),sources);
        }
        if(index.sourceCount()==0)return null;
        int cx=tx*4,cz=tz*4;
        var atlas=new ChunkLightAtlas().build(index,cx,cz,4);
        return new LodRgbLighting(atlas.bytes().asIntBuffer(),cx*16,cz*16,4);
    }
    /** 24-bit hue plus seven-bit strength in bits 1..31. Bit 0 remains the UV flag. */
    public int packed(float x,float y,float z){
        int sx=Math.min(width-1,Math.max(0,(int)Math.floor(x/16))),
            sz=Math.min(width-1,Math.max(0,(int)Math.floor(z/16)));
        int column=data.get(1+sz*width+sx);
        if(column==0)return 0;
        int sy=(int)Math.floor(y/16),row=sy-data.get(column);
        if(row<0||row>=data.get(column+1))return 0;
        int offset=data.get(column+2+row);
        if(offset==0)return 0;
        int start=column+offset;
        if(data.get(start)!=-1)return 0;
        float px=Math.clamp((x-sx*16)*.5f,0f,8f),
              py=Math.clamp((y-sy*16)*.5f,0f,8f),
              pz=Math.clamp((z-sz*16)*.5f,0f,8f);
        int ix=Math.min(7,(int)px),iy=Math.min(7,(int)py),iz=Math.min(7,(int)pz);
        float tx=px-ix,ty=py-iy,tz=pz-iz;
        float r=0,g=0,b=0,e=0;
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
            int at=start+1+2*(((iz+dz)*9+iy+dy)*9+ix+dx);
            int hue=data.get(at);
            float energy=Float.intBitsToFloat(data.get(at+1));
            float w=energy*(dx==0?1-tx:tx)*(dy==0?1-ty:ty)*(dz==0?1-tz:tz);
            e+=w;r+=(hue&255)*w;g+=(hue>>>8&255)*w;b+=(hue>>>16&255)*w;
        }
        if(e<=.00001f)return 0;
        int strength=Math.clamp(Math.round((float)(1-Math.exp(-e*1.15))*127),0,127);
        if(strength==0)return 0;
        return strength<<25|Math.clamp(Math.round(b/e),0,255)<<17
                |Math.clamp(Math.round(g/e),0,255)<<9|Math.clamp(Math.round(r/e),0,255)<<1;
    }
}