package pl.sofron.light.runtime;

import java.util.Arrays;
import java.util.List;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.LightOcclusion;

/** Multi-source, obstacle-aware connectivity mask for a two-block lighting lattice.
 * Radial intensity remains the SofronPack model; only the reachable hue is gated.
 * One flood per hue rather than one ray/flood per emitter. */
public final class StationaryReach {
    private byte[] visited=new byte[0], solid=new byte[0];
    private int[] queue=new int[0];
    private final boolean[] mask=new boolean[729];
    public boolean[] sample(List<LightSource> lights,int ox,int oy,int oz,LightOcclusion geometry){
        return sample(lights,ox,oy,oz,geometry,null);
    }
    public boolean[] sample(List<LightSource> lights,int ox,int oy,int oz,LightOcclusion geometry,float[] weights){
        Arrays.fill(mask,false);
        int remaining=0;
        for(int i=0;i<mask.length;i++)if(weights==null||weights[i]>0)remaining++;
        if(remaining==0)return mask;
        if(lights.isEmpty())return mask;
        float max=0;
        for(var s:lights)max=Math.max(max,s.radius());
        int halo=(int)Math.ceil(max)+1;
        int x0=ox-halo,y0=oy-halo,z0=oz-halo;
        int n=16+2*halo+1,plane=n*n,total=plane*n;
        if(visited.length<total){visited=new byte[total];solid=new byte[total];queue=new int[total];}
        else {Arrays.fill(visited,0,total,(byte)0);Arrays.fill(solid,0,total,(byte)0);}
        // FIFO multi-source BFS. Mixed-radius groups intentionally use the
        // group's largest reach; the original radial sum still limits intensity.
        int maxSteps=(int)Math.ceil(max*Math.sqrt(3));
        int head=0,tail=0;
        for(var s:lights){
            int x=(int)Math.floor(s.x())-x0,y=(int)Math.floor(s.y())-y0,z=(int)Math.floor(s.z())-z0;
            if(x<0||y<0||z<0||x>=n||y>=n||z>=n)continue;
            int at=y*plane+z*n+x;
            if(visited[at]==0){visited[at]=1;queue[tail++]=at;}
        }
        // Each cell is queued at most once. Cache opacity reads within this flood.
        while(head<tail){
            int at=queue[head++],dist=Byte.toUnsignedInt(visited[at]);
            int x=at%n,y=at/plane,z=at/n%n;
            int tx=x-halo+1,ty=y-halo+1,tz=z-halo+1;
            if(tx>=0&&tx<18&&ty>=0&&ty<18&&tz>=0&&tz<18){
                int node=((tz>>1)*9+(ty>>1))*9+(tx>>1);
                if(!mask[node]&&(weights==null||weights[node]>0)){
                    mask[node]=true;
                    if(--remaining==0)return mask;
                }
            }
            if(dist>maxSteps)continue;
            if(x>0)tail=offer(at-1,x-1,y,z,dist,tail,x0,y0,z0,geometry);
            if(x+1<n)tail=offer(at+1,x+1,y,z,dist,tail,x0,y0,z0,geometry);
            if(z>0)tail=offer(at-n,x,y,z-1,dist,tail,x0,y0,z0,geometry);
            if(z+1<n)tail=offer(at+n,x,y,z+1,dist,tail,x0,y0,z0,geometry);
            if(y>0)tail=offer(at-plane,x,y-1,z,dist,tail,x0,y0,z0,geometry);
            if(y+1<n)tail=offer(at+plane,x,y+1,z,dist,tail,x0,y0,z0,geometry);
        }
        return mask;
    }
    private int offer(int at,int x,int y,int z,int dist,int tail,int ox,int oy,int oz,LightOcclusion geometry){
        if(visited[at]!=0)return tail;
        if(solid[at]==0)solid[at]=(byte)(geometry.blocks(ox+x,oy+y,oz+z)?2:1);
        if(solid[at]==2)return tail;
        visited[at]=(byte)(dist+1);queue[tail++]=at;return tail;
    }
}