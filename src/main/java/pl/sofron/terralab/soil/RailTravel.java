package pl.sofron.terralab.soil;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

public final class RailTravel {
    private RailTravel() {}

    public static double distanceMultiplier(AbstractMinecart cart) {
        int y = cart.getBlockY();
        if (y >= 60 || !(cart.getFirstPassenger() instanceof Player)) return 1.0;
        return 1.0 + 0.05 * (1 + (59 - y) / 30);
    }
}
