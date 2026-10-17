package dev.solarvant.engine;

import dev.solarvant.engine.RegionMeshPipeline.Layer;

/**
 * Opaque QUADS carry the same 0-1-2,2-3-0 topology in every region and can
 * share one GL element-array buffer. Explicitly indexed opaque geometry is
 * NOT guaranteed canonical and stays on Minecraft's VBO path. Transparent
 * geometry always keeps its independently sorted section index buffer.
 */
public final class IndexStoragePolicy {
    private IndexStoragePolicy() { }

    public static boolean shared(Layer layer) {
        if (layer == null) throw new IllegalArgumentException("Missing render layer");
        return layer != Layer.TRANSLUCENT;
    }

    public static boolean canUseNative(Layer layer, boolean explicitIndexBuffer) {
        return !shared(layer) || !explicitIndexBuffer;
    }

    /** Doubling a 16-bit EBO must not cross the maximum representable vertex. */
    public static int growCanonicalIndexCount(int current, int required, int stride) {
        if (current < 0 || required < 0 || required % 6 != 0 ||
                (stride != 2 && stride != 4))
            throw new IllegalArgumentException("Invalid canonical index capacity");
        final int shortLimit = (65535 / 4) * 6;
        if (stride == 2 && required > shortLimit)
            throw new IllegalArgumentException("16-bit quad index capacity exceeded");
        int target = Math.max(6, current);
        while (target < required) {
            target = Math.multiplyExact(target, 2);
            if (stride == 2 && target > shortLimit) target = shortLimit;
        }
        return target;
    }

    public static int drawFirstIndex(Layer layer, int allocatedFirstIndex) {
        if (allocatedFirstIndex < 0) throw new IllegalArgumentException("Negative offset");
        return shared(layer) ? 0 : allocatedFirstIndex;
    }
}
