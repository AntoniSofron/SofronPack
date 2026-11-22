package pl.sofron.optimization.gui;

import java.util.ArrayList;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;

/**
 * Maintains an incremental spatial index for append-only GUI batches.
 * If a caller edits/removes elements in an unsupported way, indexedModCount
 * no longer matches the underlying ArrayList and vanilla linear search wins.
 */
public final class SofronSpatialGuiArrayList<E extends ScreenArea> extends ArrayList<E> {
    private final SofronScreenRectangleQuadTree quadTree = new SofronScreenRectangleQuadTree();
    private int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
    private int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
    private int indexedModCount;

    @Override
    public boolean add(E element) {
        int oldModCount = modCount;
        boolean added = super.add(element);
        if (indexedModCount == oldModCount) {
            ScreenRectangle bounds = element.bounds();
            if (bounds != null) index(bounds);
            indexedModCount = modCount;
        }
        return added;
    }

    @Override
    public E set(int index, E element) {
        E previous = super.set(index, element);
        indexedModCount = modCount - 1;
        return previous;
    }

    public boolean isStateValid() { return indexedModCount == modCount; }

    public boolean intersects(ScreenRectangle bounds) {
        if (left >= bounds.right() || right <= bounds.left()
                || top >= bounds.bottom() || bottom <= bounds.top()) return false;
        return quadTree.intersects(bounds);
    }

    private void index(ScreenRectangle bounds) {
        left = Math.min(bounds.left(), left);
        top = Math.min(bounds.top(), top);
        right = Math.max(bounds.right(), right);
        bottom = Math.max(bounds.bottom(), bottom);
        quadTree.add(bounds);
    }
}
