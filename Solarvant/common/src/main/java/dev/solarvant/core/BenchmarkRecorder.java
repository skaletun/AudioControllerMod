package dev.solarvant.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * Fixed-duration client frame-time benchmark. Zero heap allocation on frames.
 * Never touches the world, graphics options, chunk distance, or render queue.
 * At most 180,000 frame samples (3 minutes at 1000 FPS) fit in ~1.4 MB.
 */
public final class BenchmarkRecorder {
    private static final int LIMIT = 180_000;
    private static final long WARMUP = 10_000_000_000L;
    private static final long CAPTURE = 60_000_000_000L;
    private static final DateTimeFormatter FILENAME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    public record Result(int samples, double avgMs, double p95Ms, double p99Ms,
                         double lowFps, int over50ms, String csv) { }

    private final float[] times = new float[LIMIT];
    private long start;
    private long last;
    private int size;
    private boolean active;
    private boolean ready;

    public void begin(long now) {
        start = 0; // arm; start timer on first in-world frame
        last = 0;
        size = 0;
        active = true;
        ready = false;
    }
    public void stop() { active = false; ready = false; }
    public boolean active() { return active; }
    public double progress(long now) {
        return !active ? 0 : Math.min(1.0, Math.max(0, (double)(now - start - WARMUP) / CAPTURE));
    }
    public void frame(long now) {
        if (!active) return;
        if (last == 0) { last = now; start = now; return; }
        long elapsed = now - last;
        last = now;
        if (now - start >= WARMUP && elapsed > 0 && elapsed <= 2_000_000_000L) {
            if (size < LIMIT) times[size++] = elapsed / 1_000_000f;
        }
        if (now - start >= WARMUP + CAPTURE || size == LIMIT) {
            active = false;
            ready = true;
        }
    }
    /** Tiny handoff on the client thread. Heavy sorting/CSV formatting runs off-thread. */
    public float[] take() {
        if (!ready) return null;
        ready = false;
        return Arrays.copyOf(times, size);
    }
    public static Result analyze(float[] sample) {
        if (sample == null || sample.length == 0) return null;
        int size = sample.length;
        float[] sorted = Arrays.copyOf(sample, size);
        Arrays.sort(sorted);
        double sum = 0;
        int spikes = 0;
        StringBuilder csv = new StringBuilder(size * 9 + 40);
        csv.append("frame,frametime_ms\n");
        for (int i = 0; i < size; i++) {
            float time = sample[i];
            sum += time;
            if (time > 50) spikes++;
            csv.append(i + 1).append(',').append(Float.toString(time)).append('\n');
        }
        double p95 = quantile(sorted, .95);
        double p99 = quantile(sorted, .99);
        return new Result(size, sum / size, p95, p99,
                1000.0 / Math.max(.001, p99), spikes, csv.toString());
    }
    public static Path save(Result report, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("solarvant-" + FILENAME.format(Instant.now()) + ".csv");
        return Files.writeString(file, report.csv());
    }
    private static double quantile(float[] values, double percent) {
        double index = (values.length - 1) * percent;
        int low = (int) index;
        int high = Math.min(values.length - 1, low + 1);
        return values[low] + (values[high] - values[low]) * (index - low);
    }
}
