package dev.solarvant.core;

/**
 * Optional hysteretic backpressure for burst particles. Does not touch the
 * render distance, chunk loading, mesh generation, entity ticks or game ticks.
 * Degrades only if there is both sustained frame pressure AND particle load.
 */
public final class ParticlePressureGovernor {
    private int limit = -1;
    private int overloadedWindows;
    private int recoveredWindows;

    public void reset() {
        limit = -1;
        overloadedWindows = 0;
        recoveredWindows = 0;
    }

    public int update(int configuredLimit, int targetFps, boolean adaptive,
                      FrameMetrics.Snapshot sample, double attemptsPerTick) {
        int base = Math.max(10, configuredLimit);
        if (!adaptive || sample.samples() < 100) {
            reset();
            return base;
        }
        if (limit < 0 || limit > base) limit = base;
        // Do not reduce visual quality to treat GPU/chunk stalls with no particle load.
        boolean particlesActive = attemptsPerTick >= 12.0;
        double budgetMs = 1000.0 / Math.max(30, Math.min(240, targetFps));
        boolean stressed = particlesActive && sample.p95Ms() > budgetMs * 1.25
                && sample.p99Ms() > budgetMs * 1.50;
        boolean recovered = sample.p95Ms() < budgetMs * 0.88
                && sample.p99Ms() < budgetMs * 1.10;
        if (stressed) {
            overloadedWindows++;
            recoveredWindows = 0;
        } else if (recovered || !particlesActive) {
            overloadedWindows = 0;
            recoveredWindows++;
        } else {
            overloadedWindows = 0;
            recoveredWindows = 0;
        }
        if (overloadedWindows >= 2) {
            overloadedWindows = 0;
            limit = Math.max(Math.max(10, base / 4), (limit * 3) / 4);
        }
        if (recoveredWindows >= 4) {
            recoveredWindows = 0;
            limit = Math.min(base, Math.max(limit + 1, (limit * 5) / 4));
        }
        return limit;
    }
}
