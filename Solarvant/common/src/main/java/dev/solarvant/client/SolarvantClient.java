package dev.solarvant.client;

import dev.solarvant.core.FrameMetrics;
import dev.solarvant.core.BenchmarkRecorder;
import dev.solarvant.engine.FaceAccelerator;
import dev.solarvant.engine.ReciprocalFaceCache;
import dev.solarvant.engine.SolarvantEngine;
import dev.solarvant.engine.TintCompilerBridge;
import dev.solarvant.core.Preset;
import dev.solarvant.core.SolarvantConfig;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/** Loader-neutral controller. World/simulation/mesh jobs are never touched. */
public final class SolarvantClient {
    private static final FrameMetrics METER = new FrameMetrics();
    private static final BenchmarkRecorder BENCHMARK = new BenchmarkRecorder();
    private static volatile String benchmarkStatus = "Ready";
    private static SolarvantConfig config;
    private static FrameMetrics.Snapshot last = METER.snapshot();
    private static long lastAssessment;
    private static volatile boolean started;
    private static volatile String renderer = "Vanilla";
    private static int[] lastGraph = new int[48];
    private static String chunkStatus = "--";
    private static String cpuStatus = "--";
    private static String gcStatus = "--";
    private static String faceStatus = "--";
    private static String tintStatus = "--";
    private static String compilerStatus = "--";
    private static long lastSystemSample;
    private static long lastGC;
    private static long lastGCWindowTime;
    private static boolean hudShown;
    /** Immutable preformatted text: HUD rendering never performs number formatting. */
    private static HudText hudText = new HudText(0, "0", "0", "0 / 0 / 0 ms", "0 / 0", "--");
    public record HudText(int fps, String fpsLabel, String lowLabel, String timingLabel,
                          String spikesLabel, String heapLabel) { }
    public static HudText hudText() { return hudText; }



    private SolarvantClient() { }

    public static void rendererName(String name) {
        renderer = name;
        refreshEngineFlags();
    }
    private static void refreshEngineFlags() {
        SolarvantConfig current = config;
        boolean active = started && current != null && current.engineEnabled
                && "Vanilla".equals(renderer);
        // No pacing, no distance manipulation, no leaf translucency trade-off.
        SolarvantEngine.configure(active, false,
                current != null && current.fastOpaqueFaces, 0);
    }
    public static String rendererName() { return renderer; }
    /** Do not inject into the Sodium/Embeddium terrain path. */
    public static boolean isEngineEnabled() {
        return started && config != null && config.engineEnabled && renderer.equals("Vanilla");
    }
    public static String engineState() {
        if (!started || config == null || !config.engineEnabled) return "OFF";
        return renderer.equals("Vanilla") ? "SOLARVANT MESHING" : "EXTERNAL RENDERER";
    }
    public static boolean isNativeRendererEnabled() {
        return isEngineEnabled() && config != null && config.nativeRendererEnabled;
    }
    public static String nativeSummary() { return dev.solarvant.engine.NativeRegionEngine.debug(); }
    public static String uploadSummary() { return "VANILLA / UNTHROTTLED"; }
    public static String benchmarkStatus() { return benchmarkStatus; }
    public static void startBenchmark() {
        start();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { benchmarkStatus = "Enter a world first"; return; }
        BENCHMARK.begin(System.nanoTime());
        benchmarkStatus = "Warmup 10s, sampling 60s";
    }
    public static String faceSummary() { return faceStatus; }
    public static String tintSummary() { return tintStatus; }
    public static String compilerSummary() { return compilerStatus; }
    public static boolean tintCacheEnabled() { return started && config != null && config.cacheSectionTints && isEngineEnabled(); }
    public static void updateEngine() {
        start();
        config.validate();
        refreshEngineFlags();
        config.save();
    }

    public static void start() {
        if (started) return;
        Minecraft mc = Minecraft.getInstance();
        Path path = mc.gameDirectory.toPath().resolve("config/solarvant.properties");
        config = new SolarvantConfig(path);
        config.load();
        // Do not apply legacy stored presets during launch: preserve options.txt.
        hudShown = config.hudVisible;
        started = true;
        refreshEngineFlags();
    }

    public static SolarvantConfig settings() { start(); return config; }
    public static FrameMetrics.Snapshot metrics() { return last; }
    public static boolean isOverlayVisible() { return started && hudShown; }
    public static void toggleOverlay() {
        start();
        hudShown = !hudShown;
        config.hudVisible = hudShown;
        config.save();
    }
    public static void toggleCompactOverlay() {
        start();
        config.compactHud = !config.compactHud;
        config.save();
    }
    public static int[] graphBars() { return lastGraph; }
    public static String chunkSummary() { return chunkStatus; }
    public static String cpuSummary() { return cpuStatus; }
    public static String gcSummary() { return gcStatus; }
    public static String heapSummary() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return used / (1024 * 1024) + " / " + runtime.maxMemory() / (1024 * 1024) + " MiB";
    }
    private static void sampleMachine(Minecraft mc, long now) {
        if (now - lastSystemSample < 1_000_000_000L) return;
        lastSystemSample = now;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            cpuStatus = load < 0 ? "--" : Math.round(load * 100.0) + "% JVM";
        }
        if (mc.level != null) {
            try { chunkStatus = Integer.toString(mc.level.getChunkSource().getLoadedChunksCount()); }
            catch (Exception ex) { chunkStatus = "--"; }
        }
        long sumGC = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) sumGC += time;
        }
        if (lastGCWindowTime != 0) {
            long elapsed = now - lastGCWindowTime;
            if (elapsed > 0) gcStatus = Math.max(0, sumGC - lastGC) + " ms / " + (elapsed / 1_000_000_000L) + " s";
        }
        lastGC = sumGC;
        lastGCWindowTime = now;
    }


    public static void onFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) dev.solarvant.engine.NativeRegionEngine.resetIfWorldChanges();
        if (mc == null || mc.level == null) {
            BENCHMARK.stop();
            METER.resetClock();
            return;
        }
        if (mc.screen != null) { METER.resetClock(); return; }
        long now = System.nanoTime();
        METER.frame(now);
        BENCHMARK.frame(now);
    }

    public static void tick() {
        start();
        float[] samples = BENCHMARK.take();
        if (samples != null) {
            benchmarkStatus = "Writing benchmark...";
            java.nio.file.Path folder = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("solarvant-benchmarks");
            Thread writer = new Thread(() -> {
                try {
                    BenchmarkRecorder.Result report = BenchmarkRecorder.analyze(samples);
                    if (report == null) { benchmarkStatus = "Not enough samples"; return; }
                    java.nio.file.Path file = BenchmarkRecorder.save(report, folder);
                    benchmarkStatus = String.format(java.util.Locale.ROOT,
                            "Saved %s | P99 %.1fms | 1%% %.0f",
                            file.getFileName(), report.p99Ms(), report.lowFps());
                } catch (java.io.IOException exception) {
                    benchmarkStatus = "Benchmark save failed: " + exception.getMessage();
                }
            }, "Solarvant-Benchmark-Writer");
            writer.setDaemon(true);
            writer.start();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null) {
                return;
        }
        long now = System.nanoTime();
        if (now - lastAssessment < 2_000_000_000L) return;
        lastAssessment = now;
        last = METER.snapshot();
        // Observations are for display; never throttle chunk GPU uploads.
        if (BENCHMARK.active()) benchmarkStatus = "Recording: "
                + (int)(BENCHMARK.progress(now) * 100.0) + "%";
        lastGraph = METER.graph(48);
        if (isEngineEnabled()) {
            faceStatus = FaceAccelerator.acceleratedFaces() + " hidden / "
                    + ReciprocalFaceCache.hits() + " reciprocal hits";
            var builds = TintCompilerBridge.stats();
            long total = builds.hits() + builds.misses();
            tintStatus = builds.hits() + " / " + total + " hits";
            compilerStatus = builds.rebuilds() + " builds / " + builds.slowBuilds() + " slow";
        } else {
            faceStatus = "inactive";
            tintStatus = "inactive";
            compilerStatus = "inactive";
        }
        if (hudShown) {
            sampleMachine(mc, now);
            int fps = Math.max(0, mc.getFps());
            hudText = new HudText(fps, Integer.toString(fps),
                    Integer.toString((int) Math.round(last.onePercentLow())),
                    String.format(java.util.Locale.ROOT, "%.1f / %.1f / %.1f ms",
                            last.averageMs(), last.p95Ms(), last.p99Ms()),
                    last.longFrames() + " / " + last.samples(), heapSummary());
        }

        // NO renderDistance().set, chunk dispatcher interaction, or mesh reload here.
    }

    public static void usePreset(Preset preset) {
        start();
        config.applyPreset(preset);
        refreshEngineFlags();
        config.save();
    }

}
