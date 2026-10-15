package dev.solarvant.engine;

import dev.solarvant.engine.RegionMeshPipeline.Section;

/**
 * Immutable six-plane conservative frustum for a 16x16x16 render section.
 * Coefficients use world-space coordinates and planes point toward the interior.
 * Outside when the positive AABB support vertex is strictly behind a plane;
 * touching boundaries remain visible. Invalid planes fail OPEN.
 */
public final class SectionFrustum implements RegionMeshPipeline.Visibility {
    private final double[] coefficients;

    public SectionFrustum(double[] coefficients) {
        if (coefficients == null || coefficients.length != 24)
            throw new IllegalArgumentException("Frustum requires six four-value planes");
        this.coefficients = coefficients.clone();
    }

    public static SectionFrustum allVisible() {
        double[] planes = new double[24];
        for (int i=0;i<6;i++) planes[i*4+3]=Double.POSITIVE_INFINITY;
        return new SectionFrustum(planes);
    }

    @Override public boolean visible(Section section) {
        double lowX = section.x() * 16.0;
        double lowY = section.y() * 16.0;
        double lowZ = section.z() * 16.0;
        double hiX = lowX+16.0, hiY=lowY+16.0, hiZ=lowZ+16.0;
        for (int i=0;i<24;i+=4) {
            double a=coefficients[i],b=coefficients[i+1],c=coefficients[i+2],d=coefficients[i+3];
            if (!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(c)||Double.isNaN(d))
                continue;
            double x=a>=0?hiX:lowX;
            double y=b>=0?hiY:lowY;
            double z=c>=0?hiZ:lowZ;
            if (a*x+b*y+c*z+d < -1e-7) return false;
        }
        return true;
    }
}
