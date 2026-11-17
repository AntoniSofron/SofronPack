package pl.sofron.light.render.gpu;

public final class LightProgramBinder {
    public interface UniformApi {
        int location(int program, String name);
        void integer(int location, int value);
        void scalar(int location, float value);
    }
    private final UniformApi uniforms;
    public LightProgramBinder(UniformApi uniforms) { this.uniforms = java.util.Objects.requireNonNull(uniforms); }
    public boolean bind(int activeProgram, LightGpuManager lights, float intensity) {
        if (activeProgram <= 0 || lights == null || !Float.isFinite(intensity) || intensity < 0) return false;
        int sampler = uniforms.location(activeProgram, "spl_LightData");
        int count = uniforms.location(activeProgram, "spl_LightCount");
        int brightness = uniforms.location(activeProgram, "spl_Intensity");
        if (sampler < 0 || count < 0 || brightness < 0) return false;
        lights.bind();
        uniforms.integer(sampler, lights.textureUnit());
        uniforms.integer(count, lights.count());
        uniforms.scalar(brightness, intensity);
        return true;
    }
}
