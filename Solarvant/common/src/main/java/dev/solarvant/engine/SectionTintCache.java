package dev.solarvant.engine;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny direct-mapped, thread-owned cache for section-build tint lookups.
 *
 * Never shared between meshing threads. Keys include the exact BlockState and
 * world/snapshot object identities, absolute BlockPos (long), and tint index.
 * Entries are invalidated at section compilation boundaries, so we never
 * reuse colors across worlds, frames, chunk reloads or resource packs.
 *
 * This class intentionally has no dependency on Minecraft, JNI, OpenGL or a
 * particular mod loader and does no allocation on a lookup or insertion.
 */
public final class SectionTintCache {
    public static final int SLOTS = 128;
    private static final int MASK = SLOTS - 1;
    private final Object[] states = new Object[SLOTS];
    private final Object[] views = new Object[SLOTS];
    private final long[] positions = new long[SLOTS];
    private final int[] tintIndices = new int[SLOTS];
    private final int[] colors = new int[SLOTS];
    private final int[] generations = new int[SLOTS];
    private int generation = 1;
    private int nesting;
    private long hits;
    private long misses;
    private final LongAdder totalHits = new LongAdder();
    private final LongAdder totalMisses = new LongAdder();

    public record Counters(long hits, long misses) {}

    public void beginSection() {
        if (nesting++ == 0) {
            // Increment generation rather than zero 128 slots for every compile.
            generation++;
            if (generation == 0) {
                Arrays.fill(generations, 0);
                generation = 1;
            }
            hits = 0;
            misses = 0;
        }
    }

    public void endSection() {
        if (nesting <= 0) throw new IllegalStateException("Unbalanced SectionTintCache.endSection");
        if (--nesting == 0) {
            totalHits.add(hits);
            totalMisses.add(misses);
            // The cache is thread-local and workers may outlive a Minecraft world.
            // Drop snapshot and state references immediately rather than retaining
            // up to 128 old RenderChunkRegions until the worker is reused.
            Arrays.fill(states, null);
            Arrays.fill(views, null);
        }
    }

    public boolean isActive() { return nesting > 0; }

    private int slot(Object state, Object view, long pos, int tint) {
        long mixed = pos * 0x9E3779B97F4A7C15L;
        mixed ^= (long) System.identityHashCode(state) * 0xBF58476D1CE4E5B9L;
        mixed ^= (long) System.identityHashCode(view) * 0x94D049BB133111EBL;
        mixed ^= (long) tint * 0x632BE59BD9B4E019L;
        mixed ^= mixed >>> 33;
        return (int) mixed & MASK;
    }

    /** Store-free probe. Caller uses result() only when true. */
    public boolean find(Object state, Object view, long pos, int tint) {
        if (nesting == 0) return false;
        int index = slot(state, view, pos, tint);
        boolean found = generations[index] == generation && states[index] == state
                && views[index] == view && positions[index] == pos && tintIndices[index] == tint;
        if (found) hits++; else misses++;
        return found;
    }

    public int result(Object state, Object view, long pos, int tint) {
        return colors[slot(state, view, pos, tint)];
    }

    public void store(Object state, Object view, long pos, int tint, int rgb) {
        if (nesting == 0) return;
        int index = slot(state, view, pos, tint);
        states[index] = state;
        views[index] = view;
        positions[index] = pos;
        tintIndices[index] = tint;
        colors[index] = rgb;
        generations[index] = generation;
    }

    public Counters counters() { return new Counters(totalHits.sum(), totalMisses.sum()); }
}
