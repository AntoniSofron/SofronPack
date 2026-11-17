package pl.sofron.light.render.gpu;

public final class RenderPassLightSync {
    public interface DrawBinding {
        boolean bindForDraw();
    }
    private boolean inside;
    private long attempts, accepted;
    public synchronized boolean beforeDraw(DrawBinding binding) {
        attempts++;
        if (inside || binding == null) return false;
        inside = true;
        try {
            boolean ok = binding.bindForDraw();
            if (ok) accepted++;
            return ok;
        } finally {
            inside = false;
        }
    }
    public synchronized long attempts() { return attempts; }
    public synchronized long accepted() { return accepted; }
}
