package dev.solarvant.engine;

import dev.solarvant.client.SolarvantClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/** Only deterministic, built-in biome-tinted block colors may use the cache. */
public final class TintCompilerBridge {
    private static final Set<Block> BUILTINS;
    private static final ThreadLocal<SectionTintCache> LOCAL =
            ThreadLocal.withInitial(SectionTintCache::new);
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder REBUILDS = new LongAdder();
    private static final LongAdder BUILD_NANOS = new LongAdder();
    private static final LongAdder SLOW_BUILDS = new LongAdder();
    private static final class Timer { long started; }
    // Mutable holder avoids boxing a new Long on every section compile.
    private static final ThreadLocal<Timer> START = ThreadLocal.withInitial(Timer::new);

    static {
        Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(blocks,
                Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
                Blocks.FERN, Blocks.LARGE_FERN,
                Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES,
                Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
                Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES,
                Blocks.FLOWERING_AZALEA_LEAVES, Blocks.VINE, Blocks.SUGAR_CANE);
        BUILTINS = Collections.unmodifiableSet(blocks);
    }

    private TintCompilerBridge() {}

    public static void begin() {
        // Each compilation is isolated; no world read ever escapes the worker.
        if (SolarvantClient.tintCacheEnabled()) LOCAL.get().beginSection();
        START.get().started = System.nanoTime();
    }

    public static void end() {
        long elapsed = Math.max(0, System.nanoTime() - START.get().started);
        REBUILDS.increment();
        BUILD_NANOS.add(elapsed);
        if (elapsed >= 25_000_000L) SLOW_BUILDS.increment();
        SectionTintCache cache = LOCAL.get();
        if (cache.isActive()) {
            SectionTintCache.Counters before = cache.counters();
            cache.endSection();
            SectionTintCache.Counters after = cache.counters();
            HITS.add(after.hits() - before.hits());
            MISSES.add(after.misses() - before.misses());
        }
    }

    public static boolean cacheable(BlockState state) {
        return SolarvantClient.tintCacheEnabled() && BUILTINS.contains(state.getBlock())
                && LOCAL.get().isActive();
    }

    public static boolean find(BlockState state, BlockAndTintGetter view, BlockPos pos, int index) {
        return LOCAL.get().find(state, view, pos.asLong(), index);
    }

    public static int result(BlockState state, BlockAndTintGetter view, BlockPos pos, int index) {
        return LOCAL.get().result(state, view, pos.asLong(), index);
    }

    public static void store(BlockState state, BlockAndTintGetter view, BlockPos pos, int index, int color) {
        LOCAL.get().store(state, view, pos.asLong(), index, color);
    }

    public record Stats(long hits, long misses, long rebuilds, long buildNanos, long slowBuilds) {}
    public static Stats stats() {
        return new Stats(HITS.sum(), MISSES.sum(), REBUILDS.sum(), BUILD_NANOS.sum(), SLOW_BUILDS.sum());
    }
}
