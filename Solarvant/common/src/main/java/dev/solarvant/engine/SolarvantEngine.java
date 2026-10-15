package dev.solarvant.engine;

/**
 * Solarvant 0.8 meshing hooks. The vanilla GPU upload/draw path remains in
 * full control: no per-frame queue cap, no chunk distance or shader mutation.
 */
public final class SolarvantEngine {
    private static volatile boolean opaqueFastPath;
    private SolarvantEngine() { }
    public static void configure(boolean enabled, boolean uploads, boolean faces, int foliageMode) {
        opaqueFastPath = enabled && faces;
    }
    public static boolean faceEnabled() { return opaqueFastPath; }
}
