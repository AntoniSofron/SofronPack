/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Mixin;
import pl.sofron.optimization.lithium.SofronRunningPolicy;

@Mixin(targets = "net.minecraft.world.entity.ai.behavior.GateBehavior$RunningPolicy$1")
public abstract class SofronLithiumGateRunOneMixin implements SofronRunningPolicy {
    @Override
    public <E extends LivingEntity> void sofron$apply(
            ShufflingList<BehaviorControl<? super E>> tasks, ServerLevel level, E entity, long time) {
        for (BehaviorControl<? super E> task : tasks) {
            if (task.getStatus() == Behavior.Status.STOPPED && task.tryStart(level, entity, time)) return;
        }
    }
}
