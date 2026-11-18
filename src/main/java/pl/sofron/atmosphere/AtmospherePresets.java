package pl.sofron.atmosphere;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
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

    private static volatile Map<String, Palette> resourcePackColors = Map.of();
    private static volatile Map<String, FogRule> resourcePackFog = Map.of();

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

    public static void loadFromPacks(ResourceManager manager) {
        Map<String, Palette> palettes = new LinkedHashMap<>();
        Map<String, FogRule> fogs = new LinkedHashMap<>();

        var entries = new ArrayList<>(manager.listResources(
                "polytone/biome_modifiers", id -> id.getPath().endsWith(".json")).entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<Identifier, Resource> entry) ->
                        entry.getKey().getPath().endsWith("/default.json") ? 0 : 1)
                .thenComparing(entry -> entry.getKey().toString()));

        for (var entry : entries) {
            Identifier id = entry.getKey();
            String filename = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
            String target = id.getNamespace() + ":" + filename.substring(0, filename.length() - 5);
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                AtmospherePackDecoder.Modifier modifier = AtmospherePackDecoder.decode(json, target);

                for (String biome : modifier.targets()) {
                    if (modifier.fogColor() != null || modifier.skyColor() != null) {
                        Palette earlier = palettes.get(biome);
                        palettes.put(biome, new Palette(
                                modifier.fogColor() != null
                                        ? ARGB.vector3fFromRGB24(modifier.fogColor())
                                        : earlier == null ? null : earlier.fog(),
                                modifier.skyColor() != null
                                        ? ARGB.vector3fFromRGB24(modifier.skyColor())
                                        : earlier == null ? null : earlier.sky()));
                    }
                    if (modifier.fogStart() != null || modifier.fogEnd() != null) {
                        FogRule previous = fogs.getOrDefault(biome, FOG_RULES.get(biome));
                        fogs.put(biome, new FogRule(
                                modifier.fogStart() == null
                                        ? previous == null ? "0" : previous.start()
                                        : modifier.fogStart(),
                                modifier.fogEnd() == null
                                        ? previous == null ? "1" : previous.end()
                                        : modifier.fogEnd()));
                    }
                }
            } catch (Exception e) {
                System.err.println("[SofronPack] Ignoring invalid atmosphere preset " + id + ": " + e.getMessage());
            }
        }

        resourcePackColors = Map.copyOf(palettes);
        resourcePackFog = Map.copyOf(fogs);
        System.out.println("[SofronPack] Atmosphere loaded: "
                + (COLORS.size() + palettes.size()) + " color presets, "
                + (FOG_RULES.size() + fogs.size()) + " fog overrides");
    }

    private static String biomeAt(ClientLevel level, Vec3 pos) {
        var biome = level.getBiomeManager().getBiome(
                (int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
        if (!ENABLED) return;
        builder.addPositionalLayer(EnvironmentAttributes.FOG_COLOR, (base, pos, weights) -> {
            String biome = biomeAt(level, pos);
            Palette palette = resourcePackColors.get(biome);
            if (palette != null && palette.fog() != null) return palette.fog();
            palette = COLORS.get(biome);
            return palette == null ? base : palette.fog();
        });
        builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, weights) -> {
            String biome = biomeAt(level, pos);
            Palette palette = resourcePackColors.get(biome);
            if (palette != null && palette.sky() != null) return palette.sky();
            palette = COLORS.get(biome);
            return palette == null ? base : palette.sky();
        });
    }

    public static void applyFog(FogData fog, Camera camera, ClientLevel level,
                                float renderDistance, DeltaTracker deltaTracker) {
        if (!ENABLED || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        BlockPos pos = camera.blockPosition();
        String biome = level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("");
        FogRule rule = resourcePackFog.getOrDefault(biome, FOG_RULES.get(biome));
        if (rule == null) return;

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        double rain = level.getRainLevel(partialTick);
        long time = level.getOverworldClockTime();
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
