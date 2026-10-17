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

    public static int drawFirstIndex(Layer layer, int allocatedFirstIndex) {
        if (allocatedFirstIndex < 0) throw new IllegalArgumentException("Negative offset");
        return shared(layer) ? 0 : allocatedFirstIndex;
    }
}
