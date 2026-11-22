package pl.sofron.optimization.mixin;

import java.util.Iterator;
import java.util.List;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import pl.sofron.optimization.ai.SofronWeightedBehaviors;

@Mixin(ShufflingList.class)
public abstract class SofronWeightedTaskListMixin<T> implements SofronWeightedBehaviors<T> {
    @Shadow @Final protected List<ShufflingList.WeightedEntry<? extends T>> entries;

    @Override public Iterator<T> iterator() {
        return new IteratorView<>(entries.iterator());
    }
}
