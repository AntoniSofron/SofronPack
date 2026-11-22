/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.lithium;

import java.util.Iterator;
import net.minecraft.world.entity.ai.behavior.ShufflingList;

/** Iterate weighted task entries without allocating a new Stream on each brain tick. */
public interface SofronWeightedBehaviors<T> extends Iterable<T> {
    @SuppressWarnings("unchecked")
    static <T> Iterable<? extends T> of(ShufflingList<T> list) {
        return (SofronWeightedBehaviors<T>) list;
    }

    final class IteratorView<T> implements Iterator<T> {
        private final Iterator<ShufflingList.WeightedEntry<? extends T>> source;
        public IteratorView(Iterator<ShufflingList.WeightedEntry<? extends T>> source) {
            this.source = source;
        }
        @Override public boolean hasNext() { return source.hasNext(); }
        @Override public T next() { return source.next().getData(); }
    }
}
