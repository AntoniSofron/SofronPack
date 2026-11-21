package pl.sofron.smoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import pl.sofron.extended.ExtendedTerrainPreview;
import pl.sofron.extended.ExtendedVoxelStore;

/** Only added to runClient in the disposable smoke-test checkout. */
public final class FlightProbe implements ClientModInitializer {
    private final int[] x = {40, 40, 40, 360, 40, 40};
    private final String[] labels = {"origin", "night", "rain", "east", "return", "walk"};
    private int stage;
    private long since;
    private boolean moved;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.getConnection() == null || stage >= x.length) return;
            if (!moved) {
                if (stage == 0) {
                    mc.getConnection().sendCommand("gamemode spectator");
                    mc.getConnection().sendCommand("setblock 43 187 44 minecraft:birch_log[axis=x]");
                    mc.getConnection().sendCommand("setblock 43 189 44 minecraft:oak_leaves[persistent=true]");
                    mc.getConnection().sendCommand("setblock 43 188 44 minecraft:air");
                }
                mc.getConnection().sendCommand(stage == 1 ? "time set midnight" : "time set noon");
                mc.getConnection().sendCommand(stage == 2 ? "weather thunder" : "weather clear");
                mc.getConnection().sendCommand("tp @s " + x[stage] + " 180 42 150 16");
                if (stage == 5) {
                    mc.getConnection().sendCommand("fill 35 179 10 45 179 44 stone");
                    mc.getConnection().sendCommand("tp @s 40 180 42 180 8");
                    mc.getConnection().sendCommand("gamemode survival");
                    mc.options.bobView().set(true);
                }
                since = System.nanoTime();
                moved = true;
                return;
            }
            if (stage == 5) mc.options.keyUp.setDown(true);
            if (stage == 5 && (42 - mc.player.getZ() < 3 || !mc.player.onGround())) return;
            if (Math.abs(mc.player.getX() - x[stage]) > 1 || Math.abs(mc.player.getY() - 180) > 1
                    || System.nanoTime() - since < (stage == 5 ? 0L : 22_000_000_000L)) return;
            if (stage == 4) {
                var snapshot = ExtendedVoxelStore.get(43 >> 4, 44 >> 4);
                if (snapshot == null) return;
                short[] cells = snapshot.unpack();
                int idx = (187 - snapshot.minY()) * 256 + (44 & 15) * 16 + (43 & 15);
                if (!snapshot.palette()[cells[idx]].contains("birch_log[axis=x]")
                        || cells[idx+256] != 0 || !snapshot.palette()[cells[idx+512]].contains("oak_leaves")) return;
                System.out.println("LOD verified horizontal birch log, air gap and oak leaves after return");
            }
            String name = "lod-" + labels[stage];
            int preview = 0;
            for (int r = 0; r <= 128; r += 4)
                if (ExtendedTerrainPreview.get((x[stage] >> 4) + r, 42 >> 4) != null) preview = r;
            System.out.println("LOD_FLIGHT " + name + " x=" + mc.player.getX() + " y=" + mc.player.getY()
                    + " normal=" + mc.options.getEffectiveRenderDistance() + " fps=" + mc.getFps()
                    + " snapshots=" + ExtendedVoxelStore.count() + " previewEast=" + preview);
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    result -> System.out.println("LOD_CAPTURE " + name + " " + result.getString()));
            if (stage == 5) mc.options.keyUp.setDown(false);
            stage++;
            moved = false;
        });
    }
}
