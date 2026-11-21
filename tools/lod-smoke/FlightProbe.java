package pl.sofron.smoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import pl.sofron.extended.ExtendedTerrainPreview;
import pl.sofron.extended.ExtendedVoxelStore;

/** Only added to runClient in the disposable smoke-test checkout. */
public final class FlightProbe implements ClientModInitializer {
    private final int[] x = {40, 360, 40};
    private final String[] labels = {"origin", "east", "return"};
    private int stage;
    private long since;
    private boolean moved;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.getConnection() == null || stage >= x.length) return;
            if (!moved) {
                if (stage == 0) mc.getConnection().sendCommand("gamemode spectator");
                mc.getConnection().sendCommand("tp @s " + x[stage] + " 180 42 150 16");
                since = System.nanoTime();
                moved = true;
                return;
            }
            if (Math.abs(mc.player.getX() - x[stage]) > 1 || Math.abs(mc.player.getY() - 180) > 1
                    || System.nanoTime() - since < 22_000_000_000L) return;
            String name = "lod-" + labels[stage];
            int preview = 0;
            for (int r = 0; r <= 128; r += 4)
                if (ExtendedTerrainPreview.get((x[stage] >> 4) + r, 42 >> 4) != null) preview = r;
            System.out.println("LOD_FLIGHT " + name + " x=" + mc.player.getX() + " y=" + mc.player.getY()
                    + " normal=" + mc.options.getEffectiveRenderDistance() + " fps=" + mc.getFps()
                    + " snapshots=" + ExtendedVoxelStore.count() + " previewEast=" + preview);
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    result -> System.out.println("LOD_CAPTURE " + name + " " + result.getString()));
            stage++;
            moved = false;
        });
    }
}
