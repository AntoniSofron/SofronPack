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

        cache = null;
        resourcePackColors = Map.copyOf(palettes);
        resourcePackFog = Map.copyOf(fogs);
        System.out.println("[SofronPack] Atmosphere loaded: "
                + (COLORS.size() + palettes.size()) + " color presets, "
                + (FOG_RULES.size() + fogs.size()) + " fog overrides");
    }

    private static volatile AtmosphereBiomeBlend cache;
    private static volatile ClientLevel cachedLevel;
    private static volatile long cachedTick = Long.MIN_VALUE;
    private static volatile double cachedX, cachedZ;

    private static AtmosphereBiomeBlend blendedBiomes(ClientLevel level, Vec3 pos) {
        long tick = level.getGameTime();
        AtmosphereBiomeBlend current = cache;
        if (current != null && cachedLevel == level && cachedTick == tick
                && cachedX == pos.x && cachedZ == pos.z) {
            return current;
        }
        // Sky and atmospheric fog describe the land below the camera, not the
        // three-dimensional biome at the player's flight altitude.
        current = AtmosphereBiomeBlend.sampleSurface(level, pos.x, pos.z);
        cache = current;
        cachedLevel = level;
        cachedTick = tick;
        cachedX = pos.x;
        cachedZ = pos.z;
        return current;
    }

    private static Vector3fc colorForBiome(String biome, boolean sky) {
        Palette palette = resourcePackColors.get(biome);
        Vector3fc override = palette == null ? null : (sky ? palette.sky() : palette.fog());
        if (override != null) return override;
        palette = COLORS.get(biome);
        return palette == null ? null : (sky ? palette.sky() : palette.fog());
    }

    private static Vector3fc mixColor(ClientLevel level, Vec3 position, Vector3fc vanilla,
                                     boolean sky) {
        return blendedBiomes(level, position).blendColors(vanilla,
                biome -> colorForBiome(biome, sky));
    }

    public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        builder.addConstantLayer(EnvironmentAttributes.CLOUD_HEIGHT, base -> 320f);
        if (!ENABLED) return;
        builder.addPositionalLayer(EnvironmentAttributes.FOG_COLOR,
                (base, pos, weights) -> mixColor(level, pos, base, false));
        builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR,
                (base, pos, weights) -> mixColor(level, pos, base, true));
    }

    private static boolean isCaveBiome(String id) {
        return id.equals("minecraft:dripstone_caves")
                || id.equals("minecraft:lush_caves")
                || id.equals("minecraft:deep_dark")
                || id.equals("abundant_atmosphere:geothermal_garden")
                || id.equals("nomansland:caves")
                || id.equals("nomansland:cave_depths");
    }

    public static void applyFog(FogData fog, Camera camera, ClientLevel level,
                                float renderDistance, DeltaTracker deltaTracker) {
        if (!ENABLED || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        var blend = blendedBiomes(level, camera.position());
        BlockPos pos = camera.blockPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        // Weather strength is global, including in dry biomes where raindrops
        // are not rendered. A thunderstorm can therefore produce desert haze.
        double weather = AtmosphereFogMath.stormStrength(
                level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        long time = level.getOverworldClockTime();
        double start = 0, end = 0;
        double baseStart = fog.environmentalStart;
        double baseEnd = fog.environmentalEnd;

        // Keep the existing smooth surface-biome transition for outdoor fog.
        for (var entry : blend.weights().entrySet()) {
            FogRule rule = resourcePackFog.getOrDefault(entry.getKey(), FOG_RULES.get(entry.getKey()));
            double sampledStart = baseStart;
            double sampledEnd = baseEnd;
            if (rule != null) {
                try {
                    sampledStart = Math.max(0, AtmosphereMath.evaluate(rule.start(), time, weather, skyLight));
                    sampledEnd = Math.max(sampledStart + 2,
                            renderDistance * Math.max(0.05,
                                    AtmosphereMath.evaluate(rule.end(), time, weather, skyLight)));
                } catch (IllegalArgumentException ignored) {
                    sampledStart = baseStart;
                    sampledEnd = baseEnd;
                }
            }
            start += sampledStart * entry.getValue();
            end += sampledEnd * entry.getValue();
        }
        if (blend.weights().isEmpty()) {
            start = baseStart;
            end = baseEnd;
        }

        // A cave may lie below an ordinary plains, forest or mountain biome.
        // Surface sampling alone would never select the cave fog preset.
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        double caveWeight = AtmosphereFogMath.caveWeight(surfaceY - pos.getY(), skyLight);
        if (caveWeight > 0) {
            String currentBiome = level.getBiome(pos).unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("");
            FogRule caveRule = isCaveBiome(currentBiome)
                    ? resourcePackFog.getOrDefault(currentBiome, FOG_RULES.get(currentBiome))
                    : null;
            if (caveRule == null) {
                caveRule = resourcePackFog.getOrDefault("minecraft:dripstone_caves",
                        FOG_RULES.get("minecraft:dripstone_caves"));
            }
            // The source pack's cave default is a start distance of 10
            // and an end distance of 50% of the render distance.
            double caveStart = 10.0;
            double caveEnd = Math.max(caveStart + 2, renderDistance * 0.5);
            if (caveRule != null) {
                try {
                    caveStart = Math.max(0,
                            AtmosphereMath.evaluate(caveRule.start(), time, weather, skyLight));
                    caveEnd = Math.max(caveStart + 2,
                            renderDistance * Math.max(0.05,
                                    AtmosphereMath.evaluate(caveRule.end(), time, weather, skyLight)));
                } catch (IllegalArgumentException ignored) {
                    // Use the source pack's cave defaults.
                }
            }
            start = AtmosphereFogMath.interpolate(start, caveStart, caveWeight);
            end = AtmosphereFogMath.interpolate(end, caveEnd, caveWeight);
        }

        fog.environmentalStart = (float) Math.max(0, start);
        fog.environmentalEnd = (float) Math.max(start + 2, end);
    }
}
