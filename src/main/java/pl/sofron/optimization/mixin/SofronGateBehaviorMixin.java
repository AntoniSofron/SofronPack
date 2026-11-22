package pl.sofron.optimization.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import pl.sofron.optimization.ai.SofronRunningPolicy;
import pl.sofron.optimization.ai.SofronWeightedBehaviors;

@Mixin(GateBehavior.class)
public abstract class SofronGateBehaviorMixin<E extends LivingEntity>
        implements BehaviorControl<E> {
    @Shadow @Final private ShufflingList<BehaviorControl<? super E>> behaviors;
    @Shadow @Final private Set<MemoryModuleType<?>> exitErasedMemories;
    @Shadow private Behavior.Status status;

    /** Optimized equivalent operation. */
    @Overwrite
    public final void tickOrStop(ServerLevel level, E entity, long time) {
        boolean running = false;
        for (BehaviorControl<? super E> task : SofronWeightedBehaviors.of(behaviors)) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                task.tickOrStop(level, entity, time);
                running |= task.getStatus() == Behavior.Status.RUNNING;
            }
        }
        if (!running) doStop(level, entity, time);
    }

    /** Optimized equivalent operation. */
    @Overwrite
    public final void doStop(ServerLevel level, E entity, long time) {
        status = Behavior.Status.STOPPED;
        for (BehaviorControl<? super E> task : SofronWeightedBehaviors.of(behaviors)) {
            if (task.getStatus() == Behavior.Status.RUNNING) task.doStop(level, entity, time);
        }
        Brain<?> brain = entity.getBrain();
        for (MemoryModuleType<?> memory : exitErasedMemories) brain.eraseMemory(memory);
    }

    @WrapOperation(method = "tryStart",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/ai/behavior/GateBehavior$RunningPolicy;apply(Ljava/util/stream/Stream;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V"))
    private void sofron$replacePolicyStream(GateBehavior.RunningPolicy policy,
            Stream<BehaviorControl<? super E>> tasks, ServerLevel level, E entity,
            long time, Operation<Void> original) {
        if ((Object) policy instanceof SofronRunningPolicy optimized) {
            optimized.sofron$apply(behaviors, level, entity, time);
        } else {
            original.call(policy, tasks, level, entity, time);
        }
    }
}
