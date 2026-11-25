package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import pl.sofron.terralab.BiomePalette;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLeafPaletteTest {
    @Test void preservesSpeciesColorsInDistantMeshes() {
        check("plains", 0x80A755, 0x7FAC22, 0x619961);
        check("desert", 0x8EA347, 0xA4A628, 0x789D56);
        check("deep_frozen_ocean", 0xFFCC32, 0xC13D42, 0x619961);
        check("cold_ocean", 0x80A755, 0x68A65A, 0x619961);
        check("pale_garden", 0x8D9980, 0x878D76, 0x85987F);
    }

    private static void check(String biome, int birch, int oak, int spruce) {
        var p = BiomePalette.get("minecraft:" + biome);
        int foliage = p.id() << 24 | p.foliage();
        assertEquals(birch, ExtendedSurfaceMesh.color(p.grass(), foliage, p.water(), "minecraft:birch_leaves[distance=3]", -1));
        assertEquals(oak, ExtendedSurfaceMesh.color(p.grass(), foliage, p.water(), "minecraft:oak_leaves", -1));
        assertEquals(spruce, ExtendedSurfaceMesh.color(p.grass(), foliage, p.water(), "minecraft:spruce_leaves", -1));
    }
}
