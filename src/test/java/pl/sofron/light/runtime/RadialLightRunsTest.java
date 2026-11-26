package pl.sofron.light.runtime;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;
import static org.junit.jupiter.api.Assertions.*;

class RadialLightRunsTest {
    @Test void denseRowsEqualIndependentRadialSum(){
        for(float radius:new float[]{2f,6.8f,10f,32f,64f}){
            var lights=new ArrayList<LightSource>();
            for(int z=-3;z<20;z++)for(int x=-5;x<22;x++)
                lights.add(new LightSource(x+.5,62.5,z+.5,radius,.85f,new RgbLight(1,.44f,.17f)));
            float[] weight=new float[729],energy=new float[729];
            assertTrue(new RadialLightRuns().accumulate(lights,0,48,0,weight,energy));
            for(int k=0;k<9;k++)for(int j=0;j<9;j++)for(int i=0;i<9;i++){
                double ew=0,ee=0;
                for(var s:lights){
                    double dx=2*i-s.x(),dy=48+2*j-s.y(),dz=2*k-s.z();
                    double d=Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if(d>=radius)continue;
                    float a=s.power()*(float)Math.pow(1-d/radius,2.8);
                    ew+=a*a;ee+=a;
                }
                int at=(k*9+j)*9+i;
                assertEquals(ee,energy[at],Math.max(1e-5,ee*2e-6));
                assertEquals(ew,weight[at],Math.max(1e-5,ew*2e-6));
            }
        }
    }
    @Test void scatteredSourcesFallBack(){
        var lights=new ArrayList<LightSource>();
        for(int i=0;i<24;i++)lights.add(new LightSource(i*2+.5,64.5,.5,6.8f,1,new RgbLight(1,0,0)));
        assertFalse(new RadialLightRuns().accumulate(lights,0,64,0,new float[729],new float[729]));
    }
}