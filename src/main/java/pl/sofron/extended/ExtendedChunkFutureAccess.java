package pl.sofron.extended;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Submit generation through Minecraft's normal asynchronous chunk pipeline. */
@Mixin(ServerChunkCache.class)
public interface ExtendedChunkFutureAccess {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> sofron$getChunkFuture(
            int x, int z, ChunkStatus status, boolean create);
}
