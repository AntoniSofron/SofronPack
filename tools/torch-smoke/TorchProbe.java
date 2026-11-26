package pl.sofron.smoke;

import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import pl.sofron.light.render.gpu.SodiumRenderPassCoordinator;

public final class TorchProbe {
    private static final String[] sources = {"soul_torch", "copper_torch", "redstone_torch"};
    private static int stage;
    private static long since;

    public static void frame(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) return;
        if (stage == sources.length) { mc.execute(mc::stop); return; }
        if (since == 0) {
            String torch = sources[stage];
            server.execute(() -> {
                var source = server.createCommandSourceStack();
                var commands = server.getCommands();
                if (stage == 0) {
                    for (var cmd : new String[]{"gamemode spectator @a", "tp @a 40.5 264 40.5 0 90",
                        "fill 29 259 29 51 259 51 white_concrete", "fill 29 260 29 51 267 51 air",
                        "time set midnight", "weather clear"})
                        commands.performPrefixedCommand(source, cmd);
                }
                commands.performPrefixedCommand(source, "setblock 40 260 40 " + torch);
            });
            since = System.nanoTime();
        }
        if (System.nanoTime() - since < 15_000_000_000L || Math.abs(mc.player.getY()-264)>1) return;
        System.out.println("TORCH " + sources[stage] + " references=" + SodiumRenderPassCoordinator.diagnosticAtlasReferences()
                + " failures=" + SodiumRenderPassCoordinator.diagnosticAtlasFailures() + " draws=" + SodiumRenderPassCoordinator.diagnosticDrawSubmissions());
        try {
            var directory = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(directory);
            var capture = new ProcessBuilder("import", "-window", "root",
                    directory.resolve(sources[stage] + ".png").toString()).inheritIO().start();
            if (capture.waitFor() != 0) throw new IllegalStateException("Screen capture failed");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        stage++;
        since = 0;
    }
}
