package dev.solarvant.engine;

/**
 * Quality-aware decision kernel for the optional canopy mesh reduction.
 * Level 0 preserves vanilla; 1 only removes faces of fully interior leaf
 * blocks; 2 also removes shared faces between adjacent leaves.
 *
 * Levels 1/2 CAN change foliage translucency, and are never silently selected
 * on the Quality or Balanced presets.
 */
public final class CanopyOcclusion {
    private CanopyOcclusion() { }

    public static boolean hide(int mode, boolean currentIsVanillaLeaf,
                               boolean targetIsVanillaLeaf, boolean surrounded) {
        if (mode <= 0 || !currentIsVanillaLeaf) return false;
        if (mode >= 2 && targetIsVanillaLeaf) return true;
        return surrounded;
    }
}
