package dev.solarvant.engine;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/** Exact occlusion shortcut for a conservative list of vanilla opaque cubes. */
public final class FaceAccelerator {
    private static final Set<Block> FULL_CUBES;
    private static final LongAdder TOTAL = new LongAdder();
    private static final ThreadLocal<Counter> LOCAL = ThreadLocal.withInitial(Counter::new);
    private static final class Counter { int depth; long count; }
    static {
        Set<Block> known = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(known,
                Blocks.STONE, Blocks.GRANITE, Blocks.POLISHED_GRANITE,
                Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.ANDESITE,
                Blocks.POLISHED_ANDESITE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
                Blocks.POLISHED_DEEPSLATE, Blocks.COBBLESTONE, Blocks.STONE_BRICKS,
                Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
                Blocks.PODZOL, Blocks.MYCELIUM, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND,
                Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.CLAY, Blocks.NETHERRACK,
                Blocks.END_STONE, Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.TUFF,
                Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.BASALT, Blocks.BLACKSTONE,
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
                Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS,
                Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS,
                Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS, Blocks.IRON_BLOCK,
                Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
                Blocks.NETHERITE_BLOCK, Blocks.COPPER_BLOCK, Blocks.COAL_BLOCK,
                Blocks.LAPIS_BLOCK, Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.PRISMARINE);
        FULL_CUBES = Collections.unmodifiableSet(known);
    }
    private FaceAccelerator() { }
    public static void beginSection() {
        Counter counter = LOCAL.get();
        if (counter.depth++ == 0) counter.count = 0;
    }
    public static void endSection() {
        Counter counter = LOCAL.get();
        if (counter.depth <= 0) throw new IllegalStateException("Unbalanced face counter");
        if (--counter.depth == 0) {
            if (counter.count != 0) TOTAL.add(counter.count);
            counter.count = 0;
        }
    }
    public static boolean candidate(BlockState current) {
        return FULL_CUBES.contains(current.getBlock()) && current.canOcclude();
    }
    public static boolean canHide(BlockState current, BlockState adjacent) {
        return candidate(current) && FULL_CUBES.contains(adjacent.getBlock()) && adjacent.canOcclude();
    }
    public static void recordedHide() {
        Counter counter = LOCAL.get();
        if (counter.depth > 0) counter.count++;
    }
    public static long acceleratedFaces() { return TOTAL.sum(); }
}
