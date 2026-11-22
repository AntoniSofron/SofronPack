package pl.sofron.extended.client;

import java.util.Map;
import java.util.TreeMap;

/** Reclaimable first-fit suballocations inside a shared RenderPearl GPU page. */
public final class ExtendedArenaRanges {
    private final int size;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();

    public ExtendedArenaRanges(int size) {
        if (size <= 0) throw new IllegalArgumentException("Arena size");
        this.size = size;
        free.put(0, size);
    }

    public int allocate(int bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Allocation size");
        for (var it = free.entrySet().iterator(); it.hasNext();) {
            var range = it.next();
            if (range.getValue() < bytes) continue;
            int offset = range.getKey(), remainder = range.getValue() - bytes;
            it.remove();
            if (remainder > 0) free.put(offset + bytes, remainder);
            return offset;
        }
        return -1;
    }

    public void release(int offset, int bytes) {
        if (offset < 0 || bytes <= 0 || (long)offset + bytes > size)
            throw new IllegalArgumentException("Invalid free region");
        int start = offset, length = bytes;
        Map.Entry<Integer, Integer> prev = free.floorEntry(start);
        if (prev != null) {
            int end = prev.getKey() + prev.getValue();
            if (end > start) throw new IllegalStateException("Overlapping allocation release");
            if (end == start) {
                start = prev.getKey();
                length += prev.getValue();
                free.remove(prev.getKey());
            }
        }
        Map.Entry<Integer, Integer> next = free.ceilingEntry(start);
        if (next != null) {
            if (start + length > next.getKey())
                throw new IllegalStateException("Overlapping allocation release");
            if (start + length == next.getKey()) {
                length += next.getValue();
                free.remove(next.getKey());
            }
        }
        free.put(start, length);
    }

    public boolean isEmpty() {
        return free.size() == 1 && free.firstKey() == 0
                && free.firstEntry().getValue() == size;
    }

    public int freeBytes() {
        int sum = 0;
        for (int length : free.values()) sum += length;
        return sum;
    }
}
