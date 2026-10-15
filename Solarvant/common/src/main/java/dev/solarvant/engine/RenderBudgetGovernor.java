package dev.solarvant.engine;

/**
 * Render-thread CPU work budget. Uses measured *frame times*, never changes
 * world/chunk distance, drops tasks, sleeps, or alters the simulation.
 *
 * Each new observation is folded into an EWMA with a hysteresis band. A large
 * pending upload queue is allowed to make bounded progress even during a slow
 * scene, avoiding permanent geometry starvation after a teleport.
 */
public final class RenderBudgetGovernor {
    public record Decision(int microseconds, int minTasks, boolean underPressure) { }

    private double filteredP95 = 0;
    private boolean underPressure;

    public void reset() {
        filteredP95 = 0;
        underPressure = false;
    }

    /** Feed once per ~2 seconds, not once per render task. */
    public void observe(double p95Millis, int targetFps) {
        if (!Double.isFinite(p95Millis) || p95Millis <= 0) return;
        double p95 = Math.min(2000.0, p95Millis);
        filteredP95 = filteredP95 <= 0 ? p95 : filteredP95 * 0.72 + p95 * 0.28;
        double targetMillis = 1000.0 / Math.max(30, Math.min(240, targetFps));
        // Preserve small fluctuations: reacting to single frames creates oscillations.
        double threshold = Math.max(17.0, targetMillis * 1.45);
        if (!underPressure && filteredP95 > threshold * 1.20) underPressure = true;
        else if (underPressure && filteredP95 < threshold * 0.83) underPressure = false;
    }

    public Decision decide(int configuredMicros, int pending) {
        int baseline = Math.max(500, Math.min(8000, configuredMicros));
        if (pending <= 0) return new Decision(0, 0, underPressure);
        int budget = underPressure ? Math.max(500, (baseline * 2) / 3) : baseline;
        int progress = 1;
        if (pending >= 24) {
            progress = 2;
            // Provide backlog relief without the 12ms burst of the old scheduler.
            budget = Math.max(budget, Math.min(2500, baseline));
        }
        if (pending >= 96) {
            progress = 4;
            budget = Math.max(budget, Math.min(4000, baseline + 750));
        }
        if (pending >= 256) {
            progress = 6;
            budget = Math.max(budget, Math.min(5000, baseline + 1250));
        }
        return new Decision(Math.min(5000, budget), progress, underPressure);
    }
}
