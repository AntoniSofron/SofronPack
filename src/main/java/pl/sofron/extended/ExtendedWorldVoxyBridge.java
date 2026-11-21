package pl.sofron.extended;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional adapter: use the upstream engine through its public entry points,
 * rather than copying its renderer, shader pipeline or storage implementation.
 * Without a LOD renderer installed, SofronPack must not silently increase
 * normal Minecraft render distance to 64 chunks.
 */
public final class ExtendedWorldVoxyBridge {
    private ExtendedWorldVoxyBridge() {}

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    public static void updateSettings(boolean enabled) {
        if (!available()) return;
        try {
            Class<?> configClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object cfg = configClass.getField("CONFIG").get(null);
            setBoolean(cfg, "enabled", enabled);
            setBoolean(cfg, "enableRendering", enabled);
            if (enabled) {
                setBoolean(cfg, "ingestEnabled", true);
                cfg.getClass().getField("sectionRenderDistance").setFloat(cfg, 2.0f);
            }
            cfg.getClass().getMethod("save").invoke(cfg);
            System.out.println("[SofronPack] Voxy LOD settings synchronized (64 chunks). Rejoin the world to apply.");
        } catch (ReflectiveOperationException | LinkageError ex) {
            System.err.println("[SofronPack] Voxy backend configuration unavailable: " + ex.getMessage());
        }
    }

    private static void setBoolean(Object instance, String name, boolean value) throws ReflectiveOperationException {
        Field field = instance.getClass().getField(name);
        field.setBoolean(instance, value);
    }

    public static boolean submitGeneratedChunk(LevelChunk chunk) {
        if (!available()) return false;
        try {
            Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Method ingest = service.getMethod("tryAutoIngestChunk", LevelChunk.class);
            return Boolean.TRUE.equals(ingest.invoke(null, chunk));
        } catch (ReflectiveOperationException | LinkageError ex) {
            System.err.println("[SofronPack] Cannot ingest generated far chunk: " + ex.getMessage());
            return false;
        }
    }
}
