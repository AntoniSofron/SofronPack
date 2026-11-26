package pl.sofron.terralab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.BiomeSpecialEffects;

public record BiomePalette(int id, int sky, int fog, int water, int grass, int foliage, int birch, int oak, int spruce) {
    private static final Map<String, BiomePalette> BIOMES = new HashMap<>();
    // IDs in the CSV are persistent. Old snapshots have 0 or 255 in the high byte.
    private static final BiomePalette[] BY_ID = new BiomePalette[256];
    public static final int DEFAULT_BIRCH = 0x80A755, DEFAULT_SPRUCE = 0x619961;

    static {
        var resource = BiomePalette.class.getResourceAsStream("/assets/sofronpack/atmosphere/biome_colors.csv");
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(resource), StandardCharsets.UTF_8))) {
            reader.readLine();
            String row;
            while ((row = reader.readLine()) != null) {
                String[] fields = row.split(",");
                int id = Integer.parseInt(fields[0]);
                int birch = rgb(fields[7]);
                int spruce = fields[1].equals("pale_garden") ? 0x85987F
                        : birch == 0x8EA347 ? 0x789D56 : DEFAULT_SPRUCE;
                var palette = new BiomePalette(id, rgb(fields[2]), rgb(fields[3]), rgb(fields[4]),
                        rgb(fields[5]), rgb(fields[6]), birch, rgb(fields[8]), spruce);
                if (id < 1 || id >= 255 || BY_ID[id] != null || BIOMES.put(Identifier.parse(fields[1]).toString(), palette) != null)
                    throw new IllegalArgumentException("Duplicate or invalid biome palette: " + row);
                BY_ID[id] = palette;
            }
        } catch (java.io.IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static int rgb(String value) {
        return value.equals("-") ? -1 : Integer.parseInt(value, 16);
    }

    public static void forEach(java.util.function.BiConsumer<String, BiomePalette> consumer) {
        BIOMES.forEach(consumer);
    }
    public static BiomePalette get(String biome) { return BIOMES.get(biome); }
    public static BiomePalette fromFoliage(int packed) { return BY_ID[packed >>> 24]; }
    public static void register() {
        BiomeModifications.create(Identifier.parse("sofronpack:biome_colors")).add(ModificationPhase.REPLACEMENTS,
                biome -> get(biome.getBiomeKey().identifier().toString()) != null,
                (biome, context) -> {
                    var p = get(biome.getBiomeKey().identifier().toString());
                    var effects = context.getEffects();
                    effects.setGrassColorModifier(BiomeSpecialEffects.GrassColorModifier.NONE);
                    effects.setGrassColorOverride(p.grass());
                    effects.setFoliageColorOverride(p.foliage());
                    effects.setWaterColor(p.water());
                    if (p.sky() >= 0) effects.setSkyColor(p.sky());
                });
    }
}
