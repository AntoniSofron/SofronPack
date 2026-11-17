package pl.sofron.light.render.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public final class LwjglLightProgramBinder {
    private static final LightProgramBinder INSTANCE = new LightProgramBinder(new LightProgramBinder.UniformApi() {
        public int location(int program, String name) { return GL20.glGetUniformLocation(program, name); }
        public void integer(int location, int value) { GL20.glUniform1i(location, value); }
        public void scalar(int location, float value) { GL20.glUniform1f(location, value); }
    });
    private LwjglLightProgramBinder() {}
    public static boolean bindCurrentProgram(LightGpuManager manager, float intensity) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        return INSTANCE.bind(program, manager, intensity);
    }
}
