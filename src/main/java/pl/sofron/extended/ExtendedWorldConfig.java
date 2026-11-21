package pl.sofron.extended;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** One user setting, independent of the normal render/simulation distances. */
public final class ExtendedWorldConfig {
    public static final int DISTANT_RADIUS_CHUNKS = 128;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("sofronpack-extended-world.json");
    private static final ExtendedWorldConfig INSTANCE = read();

    public boolean enabled = false;

    private ExtendedWorldConfig() {}

    public static ExtendedWorldConfig get() { return INSTANCE; }

    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("[SofronPack] Cannot save Extended World setting: " + ex.getMessage());
        }
    }

    private static ExtendedWorldConfig read() {
        if (Files.isRegularFile(PATH)) {
            try {
                ExtendedWorldConfig value = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8),
                        ExtendedWorldConfig.class);
                if (value != null) return value;
            } catch (Exception ex) {
                System.err.println("[SofronPack] Invalid Extended World config: " + ex.getMessage());
            }
        }
        return new ExtendedWorldConfig();
    }
}
