package dev.solarvant.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional geometry-reducing culling, without extra scene draw passes.
 * Operates on the same immutable RenderChunkRegion as vanilla SectionCompiler.
 * Scope strictly follows each section rebuild; no cross-world cache or distance changes.
 */
public final class CanopyMeshCuller {
    private static final Set<Block> LEAVES;
    private static final LongAdder REMOVED_FACES = new LongAdder();
    private static final ThreadLocal<Scope> CONTEXT = ThreadLocal.withInitial(Scope::new);

    private static final class Scope {
        int nesting;
        long lastPos;
        BlockGetter lastView;
        boolean lastResult;
        boolean valid;
        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    }

    static {
        Set<Block> known = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(known, Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES,
                Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES,
                Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES,
                Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES);
        LEAVES = Collections.unmodifiableSet(known);
    }

    private CanopyMeshCuller() { }
    public static void beginSection() {
        Scope context = CONTEXT.get();
        if (context.nesting++ == 0) {
            context.valid = false;
            context.lastView = null;
        }
    }
    public static void endSection() {
        Scope context = CONTEXT.get();
        if (context.nesting <= 0) throw new IllegalStateException("Unbalanced canopy culling scope");
        if (--context.nesting == 0) {
            context.valid = false;
            context.lastView = null;
        }
    }
    public static boolean isVanillaLeaf(BlockState state) {
        return LEAVES.contains(state.getBlock()) && !state.getValue(LeavesBlock.WATERLOGGED);
    }

    private static boolean surrounded(BlockGetter region, BlockPos at, Scope ctx) {
        long packed = at.asLong();
        if (ctx.valid && ctx.lastPos == packed && ctx.lastView == region)
            return ctx.lastResult;
        ctx.valid = true;
        ctx.lastPos = packed;
        ctx.lastView = region;
        for (Direction direction : Direction.values()) {
            ctx.scratch.set(at.getX() + direction.getStepX(),
                    at.getY() + direction.getStepY(), at.getZ() + direction.getStepZ());
            if (!isVanillaLeaf(region.getBlockState(ctx.scratch))) {
                ctx.lastResult = false;
                return false;
            }
        }
        ctx.lastResult = true;
        return true;
    }

    /** Must be called inside SectionCompiler; no main-thread or entity culling. */
    public static boolean shouldHide(int mode, BlockState current, BlockGetter region,
                                     BlockPos origin, BlockPos neighbor) {
        if (mode == 0 || !isVanillaLeaf(current)) return false;
        Scope ctx = CONTEXT.get();
        if (ctx.nesting == 0) return false;
        // Interior mode leaves a complete outer shell. The aggressive option
        // removes shared leaf faces, producing a further graphics tradeoff.
        boolean hidden = mode == 1 ? surrounded(region, origin, ctx)
                : CanopyOcclusion.hide(mode, true, isVanillaLeaf(region.getBlockState(neighbor)), false);
        if (hidden) REMOVED_FACES.increment();
        return hidden;
    }
    public static long removedFaces() { return REMOVED_FACES.sum(); }
}
