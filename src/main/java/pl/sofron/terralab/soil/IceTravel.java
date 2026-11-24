package pl.sofron.terralab.soil;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public final class IceTravel {
    private IceTravel() {}

    public static float multiplier(BlockState ground, int iceY) {
        if (iceY < 70 || !ground.is(BlockTags.ICE)) return 1.0F;
        return Math.min(2.0F, 1.0F + ((iceY - 60) / 10) * 0.15F);
    }
}
