package dev.solarvant.engine;

import java.util.Queue;

/**
 * Frame-aware, in-order upload pump for Minecraft's original Runnable queue.
 * It never replaces the vertex format, discards uploads, mutates sections, or
 * crosses threads; the render dispatcher remains the exclusive GL owner.
 */
public final class UploadPacer {
    public record Stats(long completed, long limitedFrames, int lastPending,
                        long lastWorkMicros, long peakWorkMicros,
                        int budgetMicros, boolean pressure) { }

    private final RenderBudgetGovernor governor = new RenderBudgetGovernor();
    private long completed;
    private long limitedFrames;
    private int lastPending;
    private long lastWorkMicros;
    private long peakWorkMicros;
    private int lastBudget;
    private boolean lastPressure;

    public void observe(double p95Ms, int targetFps) {
        governor.observe(p95Ms, targetFps);
    }

    /** Legacy adapter for unit tests and callers that do not have metrics. */
    public int drain(Queue<Runnable> queue, int maxMicros, int maxTasks) {
        int waiting = queue.size();
        if (waiting == 0) {
            lastPending = 0;
            lastWorkMicros = 0;
            lastBudget = 0;
            return 0;
        }
        RenderBudgetGovernor.Decision plan = governor.decide(maxMicros, waiting);
        lastBudget = plan.microseconds();
        lastPressure = plan.underPressure();
        long start = System.nanoTime();
        long budgetNanos = (long)plan.microseconds() * 1_000L;
        // A single, slow upload may exceed the budget; the original Runnable
        // has no cancellation point. We still always execute at least one.
        int max = Math.max(plan.minTasks(), Math.min(256, Math.max(1, maxTasks)));
        int processed = 0;
        while (processed < max) {
            Runnable task = queue.poll();
            if (task == null) break;
            try {
                task.run();
            } finally {
                // Account for attempted execution even if vanilla's task fails;
                // the original exception is propagated to Minecraft.
                completed++;
            }
            processed++;
            if (System.nanoTime() - start >= budgetNanos) break;
        }
        long microseconds = Math.max(0L, System.nanoTime() - start) / 1_000L;
        lastWorkMicros = microseconds;
        peakWorkMicros = Math.max(peakWorkMicros, microseconds);
        lastPending = queue.size();
        if (lastPending > 0) limitedFrames++;
        return processed;
    }

    public Stats stats() {
        return new Stats(completed, limitedFrames, lastPending,
                lastWorkMicros, peakWorkMicros, lastBudget, lastPressure);
    }

    public void resetCounters() {
        completed = 0;
        limitedFrames = 0;
        lastPending = 0;
        lastWorkMicros = 0;
        peakWorkMicros = 0;
        lastBudget = 0;
        lastPressure = false;
        governor.reset();
    }
}
