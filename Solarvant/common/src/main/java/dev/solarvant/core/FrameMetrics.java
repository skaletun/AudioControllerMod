package dev.solarvant.core;

import java.util.Arrays;

/** Bounded, allocation-free per-frame hot path. Percentiles calculated only on request. */
public final class FrameMetrics {
    private static final int WINDOW = 2048;
    private final double[] times = new double[WINDOW];
    private int write;
    private int count;
    private long lastFrame;
    private int spikes;

    /** Sampled for HUD at 2Hz; never allocates inside the per-frame frame() hot path. */
    public int[] graph(int bars) {
        int n = Math.max(0, Math.min(bars, count));
        int[] out = new int[bars];
        for (int i = 0; i < n; i++) {
            int index = (write - n + i + WINDOW) % WINDOW;
            out[bars - n + i] = (int) Math.round(times[index]);
        }
        return out;
    }
    private final boolean[] spikeFlags = new boolean[WINDOW];

    public void resetClock() { lastFrame = 0; }

    public void frame(long now) {
        if (lastFrame == 0) { lastFrame = now; return; }
        long delta = now - lastFrame;
        lastFrame = now;
        // Screen transitions reset the clock in the client. Exclude only multi-second suspend gaps.
        // Genuine 250-1000ms gameplay freezes MUST count in P99 and spike metrics.
        if (delta <= 0 || delta > 2_000_000_000L) return;
        double ms = delta / 1_000_000.0;
        if (count == WINDOW && spikeFlags[write]) spikes--;
        times[write] = ms;
        spikeFlags[write] = ms > 50.0;
        if (spikeFlags[write]) spikes++;
        write = (write + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    public Snapshot snapshot() {
        if (count == 0) return new Snapshot(0, 0, 0, 0, 0, 0, 0);
        double[] sorted = Arrays.copyOf(times, count);
        Arrays.sort(sorted);
        double sum = 0;
        for (double value : sorted) sum += value;
        double average = sum / count;
        double p95 = quantile(sorted, 0.95);
        double p99 = quantile(sorted, 0.99);
        return new Snapshot(1000.0 / average, average, p95, p99,
                1000.0 / p99, count, spikes);
    }

    private static double quantile(double[] sorted, double q) {
        double i = (sorted.length - 1) * q;
        int low = (int) i;
        int high = Math.min(sorted.length - 1, low + 1);
        return sorted[low] + (sorted[high] - sorted[low]) * (i - low);
    }

    public record Snapshot(double fps, double averageMs, double p95Ms,
                           double p99Ms, double onePercentLow, int samples, int longFrames) { }
}
