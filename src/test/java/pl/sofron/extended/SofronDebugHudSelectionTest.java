package pl.sofron.extended;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SofronDebugHudSelectionTest {
    @Test void detailsAreAvailableInCreativeAndSpectatorOnly() {
        assertTrue(SofronDebugHudSelection.showDetails(true, false));
        assertTrue(SofronDebugHudSelection.showDetails(false, true));
        assertFalse(SofronDebugHudSelection.showDetails(false, false));
        assertTrue(SofronDebugHudSelection.showDetails(true, true));
    }

    @Test void selectsOnlyHighlightedCoordinatesNotFacingOrDimension() {
        List<String> original = List.of(
                "XYZ: -1381.076 / 591.15237 / 25.148",
                "Block: -1382 591 25",
                "Chunk: -87 36 1 [9 1 in r.-3.0.mca]",
                "Facing: west (Towards negative X)",
                "minecraft:overworld FC: 0",
                "Section-relative: 10 15 09"
        );
        assertEquals(original.subList(0, 3), SofronDebugHudSelection.positionOnly(original));
    }

    @Test void emptyOrUnrelatedLinesNeverShow() {
        assertEquals(List.of(), SofronDebugHudSelection.positionOnly(List.of()));
        assertEquals(List.of(), SofronDebugHudSelection.positionOnly(
                List.of("Biome: plains", "Memory: 512MB", "TPS: 20")));
    }
}
