package pl.sofron.light.runtime;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import pl.sofron.light.engine.LightOcclusion;

/** Client-thread collision memoization, in world rather than camera coordinates.
 * Retains overlap when the 33-block volume moves, with bounded section storage. */
public final class DynamicOcclusionCache implements LightOcclusion {
    private final Long2ObjectOpenHashMap<byte[]> sections=new Long2ObjectOpenHashMap<>();
    private LightOcclusion source=LightOcclusion.NONE;
    private long lastKey=Long.MIN_VALUE;
    private byte[] last;
    private static long key(int x,int y,int z){
        return ((long)x&0x3fffffL)<<42|((long)z&0x3fffffL)<<20|((long)y&0xfffffL);
    }
    public void source(LightOcclusion source){this.source=source;}
    public void clear(){sections.clear();last=null;lastKey=Long.MIN_VALUE;}
    public void retain(int x0,int y0,int z0,int size){
        // At most 4x4x4 sections for a 33-block cube. No source/range cap.
        int ax=x0>>4,ay=y0>>4,az=z0>>4,bx=(x0+size-1)>>4,by=(y0+size-1)>>4,bz=(z0+size-1)>>4;
        var it=sections.long2ObjectEntrySet().iterator();
        while(it.hasNext()){
            long k=it.next().getLongKey();
            int x=(int)(k>>42),z=(int)(k<<22>>42),y=(int)(k<<44>>44);
            if(x<ax||x>bx||y<ay||y>by||z<az||z>bz){it.remove();if(k==lastKey)last=null;}
        }
    }
    private void remove(int x,int y,int z){
        long k=key(x,y,z);sections.remove(k);if(k==lastKey)last=null;
    }
    public void invalidate(int x,int y,int z){
        // Neighbor-dependent shapes can change across section boundaries.
        for(int sy=(y-1)>>4;sy<=(y+1)>>4;sy++)
            for(int sz=(z-1)>>4;sz<=(z+1)>>4;sz++)
                for(int sx=(x-1)>>4;sx<=(x+1)>>4;sx++)remove(sx,sy,sz);
    }
    public void invalidateChunk(int x,int z){
        var it=sections.long2ObjectEntrySet().iterator();
        while(it.hasNext()){
            long k=it.next().getLongKey();
            if(Math.abs((int)(k>>42)-x)<=1&&Math.abs((int)(k<<22>>42)-z)<=1){
                it.remove();if(k==lastKey)last=null;
            }
        }
    }
    @Override public boolean blocks(int x,int y,int z){
        long k=key(x>>4,y>>4,z>>4);
        byte[] cells=last!=null&&k==lastKey?last:sections.get(k);
        if(cells==null){cells=new byte[4096];sections.put(k,cells);}
        last=cells;lastKey=k;
        int at=((y&15)*16+(z&15))*16+(x&15);
        byte value=cells[at];
        if(value==0)cells[at]=value=(byte)(source.blocks(x,y,z)?2:1);
        return value==2;
    }
}