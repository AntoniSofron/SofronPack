package pl.sofron.atmosphere;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class AtmospherePresets {
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("sofronpack.atmosphere", "true"));
    private static final Map<String, Palette> COLORS = new HashMap<>();
    private static final Map<String, FogRule> FOG_RULES = new HashMap<>();

    private record Palette(Vector3fc fog, Vector3fc sky) {}
    private record FogRule(String start, String end) {}

    static {
        try (var input = AtmospherePresets.class.getResourceAsStream(
                "/assets/sofronpack/atmosphere/earth_sky.zlib")) {
            if (input == null) throw new IllegalStateException("Missing default atmosphere presets");
            try (var reader = new InputStreamReader(new InflaterInputStream(input), StandardCharsets.UTF_8)) {
                JsonObject presets = JsonParser.parseReader(reader).getAsJsonObject();
                for (var entry : presets.getAsJsonObject("colors").entrySet()) {
                    JsonArray rgb = entry.getValue().getAsJsonArray();
                    COLORS.put(entry.getKey(), new Palette(
                            ARGB.vector3fFromRGB24(rgb.get(0).getAsInt()),
                            ARGB.vector3fFromRGB24(rgb.get(1).getAsInt())));
                }
                for (JsonElement element : presets.getAsJsonArray("fog")) {
                    JsonObject rule = element.getAsJsonObject();
                    String name = rule.get("group").getAsString();
                    String start = rule.get("start").getAsString();
                    String end = rule.get("end").getAsString();
                    FogRule fogRule = new FogRule(start, end);
                    JsonArray targets = rule.getAsJsonArray("targets");
                    if (targets.isEmpty()) {
                        FOG_RULES.put("minecraft:" + name, fogRule);
                        if (name.equals("pale_garden"))
                            FOG_RULES.put("vanillabackport:pale_garden", fogRule);
                    }
                    for (JsonElement target : targets)
                        FOG_RULES.put(target.getAsString(), fogRule);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize SofronPack atmosphere", e);
        }
    }

    private AtmospherePresets() {}

    private static String biomeAt(ClientLevel level, Vec3 pos) {
        var biome = level.getBiomeManager().getBiome(
                (int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
        if (!ENABLED) return;
        builder.addPositionalLayer(EnvironmentAttributes.FOG_COLOR, (base, pos, weights) -> {
            Palette palette = COLORS.get(biomeAt(level, pos));
            return palette == null ? base : palette.fog();
        });
        builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, weights) -> {
            Palette palette = COLORS.get(biomeAt(level, pos));
            return palette == null ? base : palette.sky();
        });
    }

    public static void applyFog(FogData fog, Camera camera, ClientLevel level,
                                float renderDistance, DeltaTracker deltaTracker) {
        if (!ENABLED || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        BlockPos pos = camera.blockPosition();
        String biome = level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("");
        FogRule rule = FOG_RULES.get(biome);
        if (rule == null) return;

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        double rain = level.getRainLevel(partialTick);
        long time = level.getDayTime();
        try {
            float start = (float)AtmosphereMath.evaluate(rule.start(), time, rain, skyLight);
            float endRatio = (float)AtmosphereMath.evaluate(rule.end(), time, rain, skyLight);
            fog.environmentalStart = Math.max(0.0F, start);
            fog.environmentalEnd = Math.max(fog.environmentalStart + 2.0F,
                    renderDistance * Math.max(0.05F, endRatio));
        } catch (IllegalArgumentException e) {
            // Invalid user-defined expressions should not break the renderer.
        }
    }
}
