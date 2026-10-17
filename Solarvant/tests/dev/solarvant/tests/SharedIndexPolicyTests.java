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
        check(IndexStoragePolicy.growCanonicalIndexCount(0, 6, 2) == 6, "short initial capacity");
        check(IndexStoragePolicy.growCanonicalIndexCount(49152, 98298, 2) == 98298,
                "short index capacity must cap before 65536 vertices");
        check(IndexStoragePolicy.growCanonicalIndexCount(49152, 98298, 4) == 98304,
                "integer index capacity can still double");
        try {
            IndexStoragePolicy.growCanonicalIndexCount(0, 98304, 2);
            throw new AssertionError("invalid 16-bit capacity accepted");
        } catch (IllegalArgumentException expected) { }
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
