package pl.sofron.terralab.travel;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public final class TravelSpeed {
    private TravelSpeed() {}

    public static float onIce(BlockState ground, int iceY) {
        if (iceY < 70 || !ground.is(BlockTags.ICE)) return 1.0F;
        return Math.min(2.0F, 1.0F + ((iceY - 60) / 10) * 0.15F);
    }

    public static double onRails(AbstractMinecart cart) {
        int y = cart.getBlockY();
        if (y >= 60 || !(cart.getFirstPassenger() instanceof Player)) return 1.0;
        return 1.0 + 0.05 * (1 + (59 - y) / 30);
    }
}
