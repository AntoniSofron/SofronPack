package pl.sofron.light.engine;

import java.util.Collection;

public final class RgbLightSampler {
    private RgbLightSampler() {}
    public static RgbLight sample(double x,double y,double z,Collection<LightSource> lights,float multiplier){
        return RgbLightField.sample(x,y,z,lights,multiplier,LightOcclusion.NONE);
    }
}
