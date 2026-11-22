/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.lithium;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ShufflingList;

/** Stream-free hot path for the two vanilla gate running policies. */
public interface SofronRunningPolicy {
    <E extends LivingEntity> void sofron$apply(ShufflingList<BehaviorControl<? super E>> tasks,
            ServerLevel level, E entity, long gameTime);
}
