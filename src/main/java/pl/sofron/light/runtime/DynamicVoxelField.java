package pl.sofron.light.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import pl.sofron.light.engine.LightOcclusion;

public final class DynamicVoxelField {
    public static final int SIZE = 33;
    public static final int CELLS = SIZE * SIZE * SIZE;
    public record Emitter(int x, int y, int z, int level, int red, int green, int blue, int tintRange) {}
    public record Snapshot(int x0, int y0, int z0, int[] voxels) {
        public int sample(int x, int y, int z) {
            int dx=x-x0, dy=y-y0, dz=z-z0;
            return dx<0||dy<0||dz<0||dx>=SIZE||dy>=SIZE||dz>=SIZE ? 0 : voxels[index(dx,dy,dz)];
        }
    }
    // Published snapshots own their packed arrays. Only traversal scratch is reused.
    private static final class Scratch {
        final byte[] obstruction = new byte[CELLS];
        final byte[] seen = new byte[CELLS];
        final int[] queue = new int[CELLS];
        final int[] colors = new int[16];
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private DynamicVoxelField() {}
    private static int index(int x,int y,int z) {return (y*SIZE+z)*SIZE+x;}
    private static final int PLANE=SIZE*SIZE;
    // Precomputed valid neighbor directions: no divisions/modulo in BFS.
    private static final byte[] FACES=new byte[CELLS];
    private static final int[] OFFSET={1,-1,PLANE,-PLANE,SIZE,-SIZE};
    private static final short[] X=new short[CELLS],Y=new short[CELLS],Z=new short[CELLS];
    static {
        for(int y=0;y<SIZE;y++)for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++){
            int at=index(x,y,z);
            X[at]=(short)x;Y[at]=(short)y;Z[at]=(short)z;
            FACES[at]=(byte)((x+1<SIZE?1:0)|(x>0?2:0)|(y+1<SIZE?4:0)
                    |(y>0?8:0)|(z+1<SIZE?16:0)|(z>0?32:0));
        }
    }
    private record Profile(int level,int red,int green,int blue,int tintRange){
        static Profile of(Emitter e){return new Profile(Math.clamp(e.level,0,15),
            Math.clamp(e.red,0,255),Math.clamp(e.green,0,255),Math.clamp(e.blue,0,255),Math.max(0,e.tintRange));}
    }
    public static Snapshot propagate(int centerX,int centerY,int centerZ,
                                     List<Emitter> emitters, LightOcclusion occlusion) {
        int x0=centerX-SIZE/2, y0=centerY-SIZE/2, z0=centerZ-SIZE/2;
        int[] packed = new int[CELLS];
        Scratch scratch = SCRATCH.get();
        byte[] obstruction = scratch.obstruction;
        byte[] seen = scratch.seen;
        int[] queue = scratch.queue;
        java.util.Arrays.fill(obstruction, (byte)0);
        java.util.Arrays.fill(seen, (byte)0);
        // Per-channel max of equal monotone attenuation profiles is exactly
        // their nearest reachable source. Seed all identical emitters at once.
        // Distinct colors, strengths or radii retain independent propagation.
        var groups=new LinkedHashMap<Profile,ArrayList<Emitter>>();
        for(var e:emitters){
            Profile p=Profile.of(e);
            if(p.level>0&&e.x>=x0&&e.x<x0+SIZE&&e.y>=y0&&e.y<y0+SIZE&&e.z>=z0&&e.z<z0+SIZE)
                groups.computeIfAbsent(p,k->new ArrayList<>()).add(e);
        }
        for(var group:groups.entrySet()){
            Profile p=group.getKey();
            int head=0,tail=0,sourceLevel=p.level;
            for(int level=1;level<=sourceLevel;level++){
                int distance=sourceLevel-level,color=0;
                if(distance<=p.tintRange){
                    float weight=1f-(float)distance/(Math.max(1,p.tintRange)+1f);
                    color=Math.round(p.red*weight)<<16|Math.round(p.green*weight)<<8|Math.round(p.blue*weight);
                }
                scratch.colors[level]=color;
            }
            for(var e:group.getValue()){
                int origin=index(e.x-x0,e.y-y0,e.z-z0);
                if(seen[origin]!=0)continue;
                seen[origin]=(byte)sourceLevel;queue[tail++]=origin;
            }
            while(head<tail){
                int at=queue[head++],level=Byte.toUnsignedInt(seen[at]);
                int old=packed[at],tint=scratch.colors[level];
                packed[at]=old==0?(level<<24)|tint:
                    (Math.max(level,old>>>24)<<24)
                    |Math.max(old&0xff0000,tint&0xff0000)
                    |Math.max(old&0xff00,tint&0xff00)
                    |Math.max(old&0xff,tint&0xff);
                if(level<=1)continue;
                int nextLevel=level-1;
                // FIFO with equal seed levels guarantees first arrival is strongest.
                for(int faces=FACES[at]&63;faces!=0;faces&=faces-1){
                    int next=at+OFFSET[Integer.numberOfTrailingZeros(faces)];
                    if(seen[next]!=0)continue;
                    if(obstruction[next]==0)obstruction[next]=(byte)(
                        occlusion.blocks(x0+X[next],y0+Y[next],z0+Z[next])?2:1);
                    if(obstruction[next]==2)continue;
                    seen[next]=(byte)nextLevel;queue[tail++]=next;
                }
            }
            for(int k=0;k<tail;k++)seen[queue[k]]=0;
        }
        return new Snapshot(x0,y0,z0,packed);
    }
    public static void put(ByteBuffer buffer, Snapshot snapshot) {
        buffer.order(ByteOrder.nativeOrder());
        buffer.putInt(snapshot.x0).putInt(snapshot.y0).putInt(snapshot.z0).putInt(SIZE);
        buffer.asIntBuffer().put(snapshot.voxels);
        buffer.position(buffer.position() + snapshot.voxels.length * Integer.BYTES);
    }
}
