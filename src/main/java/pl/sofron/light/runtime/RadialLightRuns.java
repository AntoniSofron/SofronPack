package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.sofron.light.engine.LightSource;

/** Exact radial sums for contiguous rows of identical, block-centered emitters.
 * For fixed Y and Z the X contributions are a range of a cached prefix sum.
 * This reduces a long lava row to a single operation per affected lattice node. */
final class RadialLightRuns {
    private static final int GRID=9;
    private record Run(int x0,int x1,int y,int z) {}
    private record Kernel(float radius,int cross) {}
    private record Prefix(int reach,double[] energy,double[] weight) {}
    private final Map<Kernel,Prefix> kernels = new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Kernel,Prefix> e) { return size()>1024; }
    };
    void clear(){kernels.clear();}
    boolean accumulate(List<LightSource> lights,int ox,int oy,int oz,float[] weights,float[] energy) {
        if(lights.size()<24) return false;
        LightSource first=lights.getFirst();
        float radius=first.radius(),power=first.power();
        if(radius>64 || power<=0) return false;
        for(var s:lights) {
            if(s.radius()!=radius || s.power()!=power
                    || s.x()-Math.floor(s.x())!=.5 || s.y()-Math.floor(s.y())!=.5 || s.z()-Math.floor(s.z())!=.5) return false;
        }
        var sorted=new ArrayList<>(lights);
        sorted.sort(Comparator.comparingDouble(LightSource::y).thenComparingDouble(LightSource::z).thenComparingDouble(LightSource::x));
        var runs=new ArrayList<Run>();
        int start=(int)Math.floor(sorted.getFirst().x())-ox, end=start;
        int y=(int)Math.floor(sorted.getFirst().y())-oy,z=(int)Math.floor(sorted.getFirst().z())-oz;
        for(int i=1;i<sorted.size();i++){
            var s=sorted.get(i);
            int nx=(int)Math.floor(s.x())-ox,ny=(int)Math.floor(s.y())-oy,nz=(int)Math.floor(s.z())-oz;
            if(ny==y && nz==z && nx==end+1){end=nx;continue;}
            runs.add(new Run(start,end,y,z));start=end=nx;y=ny;z=nz;
        }
        runs.add(new Run(start,end,y,z));
        // Scattered lamps are cheaper with the ordinary per-source kernel.
        if(runs.size()*2>lights.size())return false;
        double r2=4.0*radius*radius;
        for(var run:runs){
            int za=Math.max(0,(int)Math.ceil((run.z+.5-radius)/2)),
                zb=Math.min(8,(int)Math.floor((run.z+.5+radius)/2));
            int ya=Math.max(0,(int)Math.ceil((run.y+.5-radius)/2)),
                yb=Math.min(8,(int)Math.floor((run.y+.5+radius)/2));
            int xa=Math.max(0,(int)Math.ceil((run.x0+.5-radius)/2)),
                xb=Math.min(8,(int)Math.floor((run.x1+.5+radius)/2));
            for(int k=za;k<=zb;k++)for(int j=ya;j<=yb;j++){
                int dy=4*j-2*run.y-1,dz=4*k-2*run.z-1;
                int cross=dy*dy+dz*dz;
                if(cross>=r2)continue;
                Prefix p=kernels.computeIfAbsent(new Kernel(radius,cross),RadialLightRuns::prefix);
                for(int i=xa;i<=xb;i++){
                    int lo=Math.max(-p.reach,2*i-run.x1),hi=Math.min(p.reach,2*i-run.x0);
                    if(lo>hi)continue;
                    int a=lo+p.reach,b=hi+p.reach+1,at=(k*GRID+j)*GRID+i;
                    energy[at]+=(float)((p.energy[b]-p.energy[a])*power);
                    weights[at]+=(float)((p.weight[b]-p.weight[a])*power*power);
                }
            }
        }
        return true;
    }
    private static Prefix prefix(Kernel k){
        int reach=(int)Math.ceil(k.radius)+1;
        double[] energy=new double[2*reach+2],weight=new double[energy.length];
        for(int d=-reach;d<=reach;d++){
            int dx=2*d-1,i=d+reach;
            double distance=Math.sqrt((dx*dx+k.cross)*.25);
            // Same attenuation as the existing lattice kernel; double prefix
            // sums prevent cancellation when taking short ranges near an edge.
            float a=distance>=k.radius?0f:(float)Math.pow(1-distance/k.radius,2.8);
            energy[i+1]=energy[i]+a;weight[i+1]=weight[i]+(double)a*a;
        }
        return new Prefix(reach,energy,weight);
    }
}