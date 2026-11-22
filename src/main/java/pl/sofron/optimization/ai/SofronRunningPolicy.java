/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
 */
package pl.sofron.optimization.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ShufflingList;

/** Stream-free hot path for the two vanilla gate running policies. */
public interface SofronRunningPolicy {
    <E extends LivingEntity> void sofron$apply(ShufflingList<BehaviorControl<? super E>> tasks,
            ServerLevel level, E entity, long gameTime);
}
