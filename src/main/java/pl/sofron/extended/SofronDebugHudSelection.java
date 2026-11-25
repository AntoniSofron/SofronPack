package pl.sofron.extended;

import java.util.ArrayList;
import java.util.List;

/** Only the three player-position lines marked on the F3 screenshot are shown. */
public final class SofronDebugHudSelection {
    private SofronDebugHudSelection() {}

    public static boolean showDetails(boolean spectator, boolean creative) {
        return spectator || creative;
    }

    public static List<String> positionOnly(List<String> lines) {
        List<String> selected = new ArrayList<>(3);
        for (String line : lines) {
            if (line.startsWith("XYZ: ") || line.startsWith("Block: ")
                    || line.startsWith("Chunk: ")) selected.add(line);
        }
        return selected;
    }
}
