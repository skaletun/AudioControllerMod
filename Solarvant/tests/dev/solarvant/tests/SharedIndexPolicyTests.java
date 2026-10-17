package dev.solarvant.tests;

import dev.solarvant.engine.IndexStoragePolicy;
import dev.solarvant.engine.RegionMeshPipeline.Layer;

public final class SharedIndexPolicyTests {
    public static void main(String[] args) {
        for (Layer layer : Layer.values()) {
            boolean transparent = layer == Layer.TRANSLUCENT;
            check(IndexStoragePolicy.shared(layer) != transparent, "private indices only for transparent");
            check(IndexStoragePolicy.canUseNative(layer, false), "implicit quads safe");
            check(IndexStoragePolicy.canUseNative(layer, true) == transparent,
                    "noncanonical opaque indices require fallback");
            check(IndexStoragePolicy.drawFirstIndex(layer, 123) == (transparent ? 123 : 0),
                    "shared element offsets must be zero");
        }
        try {
            IndexStoragePolicy.drawFirstIndex(Layer.SOLID, -1);
            throw new AssertionError("negative index offset accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: shared opaque EBO policy and transparent index ownership");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
