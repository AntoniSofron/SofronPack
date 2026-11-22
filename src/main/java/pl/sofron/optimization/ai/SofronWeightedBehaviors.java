package pl.sofron.optimization.ai;

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
