/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import java.util.Iterator;
import java.util.List;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import pl.sofron.optimization.lithium.SofronWeightedBehaviors;

@Mixin(ShufflingList.class)
public abstract class SofronLithiumShufflingListMixin<T> implements SofronWeightedBehaviors<T> {
    @Shadow @Final protected List<ShufflingList.WeightedEntry<? extends T>> entries;

    @Override public Iterator<T> iterator() {
        return new IteratorView<>(entries.iterator());
    }
}
