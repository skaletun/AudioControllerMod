package dev.solarvant.engine;

import java.util.concurrent.atomic.LongAdder;

/**
 * Exact section-local memo for *already proved* occluded opaque cube faces.
 * When A's east face is occluded by B, B's west face is occluded too. Both
 * Block.shouldRenderFace calls are normally made independently by the section
 * builder, and each invokes a BlockGetter lookup. This class stores that result
 * for the reciprocal call without retaining a world, block state or position.
 *
 * Not a general-purpose visibility cache: translucent blocks, model-specific
 * geometry, fluids, modded blocks and non-section callers never enter it.
 */
public final class ReciprocalFaceCache {
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder STORES = new LongAdder();
    private static final ThreadLocal<ReciprocalFaceCache> LOCAL =
            ThreadLocal.withInitial(ReciprocalFaceCache::new);

    private final long[] positions = new long[SLOTS];
    private final byte[] faces = new byte[SLOTS];
    private final int[] generations = new int[SLOTS];
    private Object view;
    private int epoch = 1;
    private int nesting;
    private long localHits;
    private long localStores;

    public static void beginSection() {
        ReciprocalFaceCache cache = LOCAL.get();
        if (cache.nesting++ == 0) {
            cache.nextGeneration();
            cache.view = null;
            cache.localHits = 0;
            cache.localStores = 0;
        }
    }

    public static void endSection() {
        ReciprocalFaceCache cache = LOCAL.get();
        if (cache.nesting == 0) throw new IllegalStateException("Unbalanced face-cache scope");
        if (--cache.nesting == 0) {
            cache.view = null;  // never retain a region snapshot or client world
            cache.nextGeneration();
            if (cache.localHits != 0) HITS.add(cache.localHits);
            if (cache.localStores != 0) STORES.add(cache.localStores);
            cache.localHits = 0;
            cache.localStores = 0;
        }
    }

    public static boolean check(Object worldView, long packedPosition, int face) {
        ReciprocalFaceCache cache = LOCAL.get();
        if (cache.nesting == 0 || cache.view != worldView) return false;
        int slot = slot(packedPosition, face);
        boolean hit = cache.generations[slot] == cache.epoch
                && cache.positions[slot] == packedPosition && cache.faces[slot] == face;
        if (hit) cache.localHits++;
        return hit;
    }

    public static void rememberPair(Object worldView, long aPos, int aFace,
                                    long bPos, int bFace) {
        ReciprocalFaceCache cache = LOCAL.get();
        if (cache.nesting == 0) return;
        if (cache.view != worldView) {
            cache.nextGeneration();
            cache.view = worldView;
        }
        cache.store(aPos, aFace);
        cache.store(bPos, bFace);
        cache.localStores += 2;
    }

    private void store(long pos, int face) {
        int slot = slot(pos, face);
        positions[slot] = pos;
        faces[slot] = (byte) face;
        generations[slot] = epoch;
    }

    private void nextGeneration() {
        if (++epoch == 0) {
            java.util.Arrays.fill(generations, 0);
            epoch = 1;
        }
    }

    private static int slot(long packed, int face) {
        long hash = packed ^ ((long) face * 0x9e3779b97f4a7c15L);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return (int) hash & MASK;
    }

    public static long hits() { return HITS.sum(); }
    public static long stores() { return STORES.sum(); }
}
