/*
 * Adapted for SofronPack from ImmediatelyFast 26.3:
 * https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 * Licensed under GNU LGPL v3 or later.
 * Source attribution and license details: THIRD_PARTY_OPTIMIZATIONS.md
 */
package pl.sofron.optimization.gui;

import java.util.Arrays;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Exact rectangle quadtree: does not approximate GUI clipping or hide visible elements. */
public final class SofronScreenRectangleQuadTree {
    private static final int CHILD_COUNT = 4;
    private Node root;

    public void add(ScreenRectangle bounds) {
        if (root == null) createRoot(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
        else ensureRootContains(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
        root.add(bounds, 0);
    }

    public boolean intersects(ScreenRectangle bounds) {
        return root != null && root.intersects(bounds);
    }

    private void createRoot(long left, long top, long right, long bottom) {
        long extent = Math.max(1L, Math.max(right - left, bottom - top));
        long size = 1L << (Long.SIZE - Long.numberOfLeadingZeros(extent - 1));
        root = new Node(left, top, size);
    }

    private void ensureRootContains(long left, long top, long right, long bottom) {
        while (left < root.x || top < root.y || right > root.x + root.size || bottom > root.y + root.size) {
            Node previous = root;
            boolean growLeft = left < previous.x, growUp = top < previous.y;
            if (previous.children == null) {
                if (growLeft) previous.x -= previous.size;
                if (growUp) previous.y -= previous.size;
                previous.size *= 2;
            } else {
                root = new Node(growLeft ? previous.x - previous.size : previous.x,
                        growUp ? previous.y - previous.size : previous.y, previous.size * 2);
                root.children = new Node[CHILD_COUNT];
                root.children[(growLeft ? 1 : 0) | (growUp ? 2 : 0)] = previous;
            }
        }
    }

    private static final class Node {
        private static final int LEAF_CAPACITY = 12, MAX_DEPTH = 12;
        private long x, y, size;
        private ScreenRectangle[] entries;
        private int entryCount;
        private Node[] children;

        Node(long x, long y, long size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }

        void add(ScreenRectangle bounds, int depth) {
            Node node = this;
            while (node.children != null || node.shouldSplit(depth)) {
                if (node.children == null) node.split(depth);
                int quadrant = node.getContainingQuadrant(bounds);
                if (quadrant < 0) break;
                node = node.getOrCreateChild(quadrant);
                depth++;
            }
            node.addEntry(bounds);
        }

        boolean shouldSplit(int depth) {
            return children == null && entryCount >= LEAF_CAPACITY && depth < MAX_DEPTH && size > 1;
        }

        void split(int depth) {
            children = new Node[CHILD_COUNT];
            for (int i = entryCount - 1; i >= 0; i--) {
                ScreenRectangle entry = entries[i];
                int quadrant = getContainingQuadrant(entry);
                if (quadrant >= 0) {
                    getOrCreateChild(quadrant).add(entry, depth + 1);
                    System.arraycopy(entries, i + 1, entries, i, entryCount - i - 1);
                    entries[--entryCount] = null;
                }
            }
        }

        void addEntry(ScreenRectangle bounds) {
            if (entries == null) entries = new ScreenRectangle[LEAF_CAPACITY];
            else if (entryCount == entries.length) entries = Arrays.copyOf(entries, entryCount * 2);
            entries[entryCount++] = bounds;
        }

        boolean intersects(ScreenRectangle bounds) {
            if (bounds.left() >= x + size || bounds.right() <= x
                    || bounds.top() >= y + size || bounds.bottom() <= y) return false;
            for (int i = 0; i < entryCount; i++)
                if (entries[i].intersects(bounds)) return true;
            if (children != null)
                for (Node child : children)
                    if (child != null && child.intersects(bounds)) return true;
            return false;
        }

        Node getOrCreateChild(int quadrant) {
            if (children[quadrant] == null) {
                long half = size / 2;
                children[quadrant] = new Node(
                        x + ((quadrant & 1) != 0 ? half : 0),
                        y + ((quadrant & 2) != 0 ? half : 0), half);
            }
            return children[quadrant];
        }

        int getContainingQuadrant(ScreenRectangle bounds) {
            long midX = x + size / 2, midY = y + size / 2;
            int column = bounds.right() <= midX ? 0 : bounds.left() >= midX ? 1 : -1;
            int row = bounds.bottom() <= midY ? 0 : bounds.top() >= midY ? 2 : -1;
            return column < 0 || row < 0 ? -1 : column | row;
        }
    }
}
