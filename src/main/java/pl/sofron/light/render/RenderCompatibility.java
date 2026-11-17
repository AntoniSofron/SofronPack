package pl.sofron.light.render;

public final class RenderCompatibility {
    public enum Backend { VANILLA, SODIUM, IRIS_SHADERPACK, UNKNOWN }
    public record Environment(boolean sodiumLoaded, boolean irisLoaded, boolean irisShaderpackActive) {}
    public static Backend choose(Environment env) {
        if (env.irisShaderpackActive()) return Backend.IRIS_SHADERPACK;
        if (env.sodiumLoaded()) return Backend.SODIUM;
        if (!env.irisLoaded()) return Backend.VANILLA;
        return Backend.UNKNOWN;
    }
    private RenderCompatibility() {}
}
