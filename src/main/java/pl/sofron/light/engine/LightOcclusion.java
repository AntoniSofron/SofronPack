package pl.sofron.light.engine;

@FunctionalInterface
public interface LightOcclusion {
    boolean blocks(int x, int y, int z);
    LightOcclusion NONE = (x, y, z) -> false;
}
