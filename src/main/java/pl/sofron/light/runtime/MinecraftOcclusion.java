package pl.sofron.light.runtime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import pl.sofron.light.engine.LightOcclusion;

public final class MinecraftOcclusion implements LightOcclusion {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public MinecraftOcclusion(ClientLevel level) { this.level = level; }

    @Override public boolean blocks(int x, int y, int z) {
        cursor.set(x, y, z);
        if (!level.hasChunkAt(cursor)) return true;
        return !level.getBlockState(cursor)
        .getCollisionShape(level, cursor)
        .isEmpty();
    }
}
