package pl.sofron.light.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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
    private static final int[] DX={1,-1,0,0,0,0};
    private static final int[] DY={0,0,1,-1,0,0};
    private static final int[] DZ={0,0,0,0,1,-1};
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
        for (Emitter emitter:emitters) {
            int sx=emitter.x-x0, sy=emitter.y-y0, sz=emitter.z-z0;
            if(sx<0||sy<0||sz<0||sx>=SIZE||sy>=SIZE||sz>=SIZE)continue;
            int origin=index(sx,sy,sz);
            int head=0, tail=0;
            int sourceLevel=Math.max(0,Math.min(15,emitter.level));
            if(sourceLevel==0)continue;
            for (int level = 1; level <= sourceLevel; level++) {
                int distance = sourceLevel - level;
                int color = 0;
                if (distance <= Math.max(0, emitter.tintRange)) {
                    float weight = 1f - (float)distance / (Math.max(1, emitter.tintRange) + 1f);
                    color = Math.round(Math.clamp(emitter.red, 0, 255) * weight) << 16
                          | Math.round(Math.clamp(emitter.green, 0, 255) * weight) << 8
                          | Math.round(Math.clamp(emitter.blue, 0, 255) * weight);
                }
                scratch.colors[level] = color;
            }
            seen[origin]=(byte)sourceLevel;
            queue[tail++]=origin;
            while(head < tail) {
                int at=queue[head++];
                int level=Byte.toUnsignedInt(seen[at]);
                int old=packed[at];
                int tint = scratch.colors[level];
                if (old == 0) {
                    packed[at] = (level << 24) | tint;
                } else {
                    packed[at] = (Math.max(level, old >>> 24) << 24)
                            | Math.max(old & 0xff0000, tint & 0xff0000)
                            | Math.max(old & 0xff00, tint & 0xff00)
                            | Math.max(old & 0xff, tint & 0xff);
                }
                int vx=at%SIZE, vy=at/(SIZE*SIZE), vz=(at/SIZE)%SIZE;
                if(level<=1) continue;
                for(int face=0;face<6;face++) {
                    int nx=vx+DX[face],ny=vy+DY[face],nz=vz+DZ[face];
                    if(nx<0||ny<0||nz<0||nx>=SIZE||ny>=SIZE||nz>=SIZE)continue;
                    int next=index(nx,ny,nz);
                    int nextLevel=level-1;
                    if(Byte.toUnsignedInt(seen[next])>=nextLevel)continue;
                    if(obstruction[next]==0) obstruction[next]=(byte)(occlusion.blocks(x0+nx,y0+ny,z0+nz)?2:1);
                    if(obstruction[next]==2)continue;
                    seen[next]=(byte)nextLevel;
                    queue[tail++]=next;
                }
            }
            for (int k=0; k<tail; k++) seen[queue[k]]=0;
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
