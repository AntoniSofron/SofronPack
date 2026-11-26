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
        Arrays.fill(mask,false);
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
            if(dist>maxSteps)continue;
            int x=at%n,y=at/plane,z=at/n%n;
            if(x>0)tail=offer(at-1,x-1,y,z,dist,tail,x0,y0,z0,geometry);
            if(x+1<n)tail=offer(at+1,x+1,y,z,dist,tail,x0,y0,z0,geometry);
            if(z>0)tail=offer(at-n,x,y,z-1,dist,tail,x0,y0,z0,geometry);
            if(z+1<n)tail=offer(at+n,x,y,z+1,dist,tail,x0,y0,z0,geometry);
            if(y>0)tail=offer(at-plane,x,y-1,z,dist,tail,x0,y0,z0,geometry);
            if(y+1<n)tail=offer(at+plane,x,y+1,z,dist,tail,x0,y0,z0,geometry);
        }
        for(int k=0;k<9;k++)for(int j=0;j<9;j++)for(int i=0;i<9;i++){
            int x=halo+2*i,y=halo+2*j,z=halo+2*k;
            boolean found=false;
            // Nodes lie on block corners. Sample the neighboring air cells.
            for(int dy=-1;dy<=0&&!found;dy++)for(int dz=-1;dz<=0&&!found;dz++)for(int dx=-1;dx<=0;dx++)
                if(visited[(y+dy)*plane+(z+dz)*n+x+dx]!=0){found=true;break;}
            mask[(k*9+j)*9+i]=found;
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