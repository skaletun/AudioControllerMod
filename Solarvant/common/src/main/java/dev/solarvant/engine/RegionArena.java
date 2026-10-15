package dev.solarvant.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic sub-allocator for a future Solarvant region GPU buffer.
 * This allocator does not make GL calls; the render backend is responsible
 * for GPU storage, fences and deferred frees. All mutation is single-threaded.
 * The current Minecraft renderer still owns its VertexBuffers.
 */
public final class RegionArena {
    public record Allocation(long id, int offset, int length, int capacity) { }
    public record Stats(int capacity, int used, int reserved, int free,
                        int largestFree, int allocations, int freeSegments) { }

    private final int capacity;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private final Map<Long, Allocation> live = new HashMap<>();
    private long nextId = 1;
    private int reserved;
    private int used;

    public RegionArena(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        free.put(0, capacity);
    }

    public Allocation allocate(int size, int alignment) {
        if (size <= 0 || alignment <= 0)
            throw new IllegalArgumentException("size or alignment invalid");
        for (Map.Entry<Integer,Integer> range : free.entrySet()) {
            int start = range.getKey();
            int extent = range.getValue();
            long aligned = ((long) start + alignment - 1L) / alignment * alignment;
            long end = aligned + size;
            if (end > (long)start + extent) continue;
            free.remove(start);
            if (aligned > start) free.put(start, (int)aligned - start);
            int after = (int)((long)start + extent - end);
            if (after > 0) free.put((int)end, after);
            int consumed = (int)(end - start);
            int padding = (int)aligned - start;
            // Padding remains free; reserved tracks only the actual allocation.
            reserved += size;
            used += size;
            Allocation allocation = new Allocation(nextId++, (int)aligned, size, size);
            live.put(allocation.id(), allocation);
            return allocation;
        }
        return null;
    }

    public void free(Allocation allocation) {
        if (allocation == null) throw new IllegalArgumentException("null allocation");
        Allocation owned = live.get(allocation.id());
        if (owned == null || !owned.equals(allocation))
            throw new IllegalStateException("Unknown, stale or already freed handle");
        // Validate before removing: a stale/forged same-id handle must not
        // invalidate an otherwise-live GPU allocation.
        live.remove(allocation.id());
        reserved -= owned.capacity();
        used -= owned.length();
        int start = owned.offset();
        int length = owned.capacity();
        Map.Entry<Integer,Integer> lower = free.lowerEntry(start);
        if (lower != null && (long)lower.getKey() + lower.getValue() == start) {
            start = lower.getKey();
            length += lower.getValue();
            free.remove(lower.getKey());
        }
        Map.Entry<Integer,Integer> higher = free.ceilingEntry(start);
        if (higher != null && (long)start + length == higher.getKey()) {
            length += higher.getValue();
            free.remove(higher.getKey());
        }
        free.put(start, length);
    }

    public boolean isLive(Allocation allocation) {
        return allocation != null && allocation.equals(live.get(allocation.id()));
    }

    public Stats stats() {
        int largest = 0;
        for (int extent : free.values()) largest = Math.max(largest, extent);
        return new Stats(capacity, used, reserved, capacity - reserved,
                largest, live.size(), free.size());
    }

    /** Throws on overlaps, accounting drift, or unmerged adjacent free extents. */
    public void verify() {
        long cursor = 0;
        long tally = 0;
        TreeMap<Integer, Integer> all = new TreeMap<>(free);
        for (Allocation a : live.values()) {
            if (all.put(a.offset(), -a.capacity()) != null)
                throw new IllegalStateException("duplicate start");
        }
        for (Map.Entry<Integer, Integer> range : all.entrySet()) {
            if (range.getKey() != cursor || range.getValue() == 0)
                throw new IllegalStateException("hole or overlapping range at " + cursor);
            cursor += Math.abs((long)range.getValue());
            if (range.getValue() < 0) tally -= range.getValue();
        }
        if (cursor != capacity || tally != reserved || reserved != used)
            throw new IllegalStateException("arena capacity/accounting error");
    }
}
