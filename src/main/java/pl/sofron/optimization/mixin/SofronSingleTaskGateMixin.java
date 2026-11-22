/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
 */
package pl.sofron.optimization.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Mixin;
import pl.sofron.optimization.ai.SofronRunningPolicy;

@Mixin(targets = "net.minecraft.world.entity.ai.behavior.GateBehavior$RunningPolicy$1")
public abstract class SofronSingleTaskGateMixin implements SofronRunningPolicy {
    @Override
    public <E extends LivingEntity> void sofron$apply(
            ShufflingList<BehaviorControl<? super E>> tasks, ServerLevel level, E entity, long time) {
        for (BehaviorControl<? super E> task : tasks) {
            if (task.getStatus() == Behavior.Status.STOPPED && task.tryStart(level, entity, time)) return;
        }
    }
}
