package dev.solarvant.tests;

import dev.solarvant.core.FrameMetrics;
import dev.solarvant.core.BenchmarkRecorder;
import dev.solarvant.core.ParticleBudget;
import dev.solarvant.core.ParticlePressureGovernor;
import dev.solarvant.core.Preset;
import dev.solarvant.core.SolarvantConfig;
import dev.solarvant.engine.UploadPacer;
import dev.solarvant.engine.RenderBudgetGovernor;
import dev.solarvant.engine.CanopyOcclusion;
import dev.solarvant.engine.SectionTintCache;
import dev.solarvant.engine.RegionArena;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.solarvant.engine.ReciprocalFaceCache;

public final class CoreTests {
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
    private static FrameMetrics.Snapshot frameSeries(long deltaMs) {
        FrameMetrics m = new FrameMetrics();
        long time = 1_000_000_000L;
        for (int i = 0; i < 400; i++) { m.frame(time); time += deltaMs * 1_000_000L; }
        return m.snapshot();
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("solarvant-test");
        Path conf = temp.resolve("config/solarvant.properties");
        SolarvantConfig original = new SolarvantConfig(conf);
        original.applyPreset(Preset.PERFORMANCE);
        check(original.engineEnabled && original.fastOpaqueFaces && original.cacheSectionTints,
                "performance strategy only changes safe meshing flags");
        check(!original.hudVisible, "presets do not enable diagnostic HUD implicitly");
        original.hudVisible = true;
        original.compactHud = true;
        original.save();
        String payload = Files.readString(conf);
        for (String property : new String[] {"renderDistance=", "fastGraphics=", "cloudsOff=",
                "entityPercent=", "particleLevel=", "canopyCulling=", "pacedUploads=",
                "uploadBudgetMicros=", "adaptive=", "maxDistance="}) {
            check(!payload.contains(property), "vanilla/legacy property must not be persisted: " + property);
        }
        SolarvantConfig loaded = new SolarvantConfig(conf);
        loaded.load();
        check(loaded.preset == Preset.PERFORMANCE, "engine-only preset persists");
        check(loaded.engineEnabled && loaded.fastOpaqueFaces && loaded.cacheSectionTints,
                "safe meshing flags persisted");
        check(loaded.hudVisible && loaded.compactHud, "HUD preferences persisted");
        // Old config must NEVER re-enable the old artificial mesh pacer or foliage reduction.
        Files.writeString(conf, "preset=PERFORMANCE\npacedUploads=true\ncanopyCulling=2\n" +
                "renderDistance=6\nfastGraphics=true\nparticleLevel=2\n");
        loaded.load();
        check(loaded.engineEnabled, "legacy settings do not disable meshing");
        loaded.save();
        payload = Files.readString(conf);
        check(!payload.contains("renderDistance=") && !payload.contains("pacedUploads="),
                "clean config after old settings migration");
        // Exact frame-time sampling: no allocations on the frame loop and no clock tick in menu.
        BenchmarkRecorder benchmark = new BenchmarkRecorder();
        long t = 1_000_000_000L;
        benchmark.begin(t);
        benchmark.frame(t);
        benchmark.frame(t + 11_000_000_000L);
        for (int i = 1; i <= 6000; i++) benchmark.frame(t + 11_000_000_000L + i * 10_000_000L);
        BenchmarkRecorder.Result report = BenchmarkRecorder.analyze(benchmark.take());
        check(report != null && report.samples() > 5000, "60s raw benchmark data captured");
        check(report.p99Ms() >= 9.99 && report.p99Ms() <= 10.01, "independent P99 computed");
        check(report.csv().startsWith("frame,frametime_ms\n"), "benchmark CSV format");
        // Include even pathological multi-second stalls; never cherry-pick a
        // benchmark by discarding the worst frames.
        BenchmarkRecorder longStall = new BenchmarkRecorder();
        long anchor = 7_000_000_000L;
        longStall.begin(anchor);
        longStall.frame(anchor);
        longStall.frame(anchor + 11_000_000_000L);
        longStall.frame(anchor + 13_500_000_000L);
        longStall.frame(anchor + 14_000_000_000L);
        for (int i = 1; i <= 57; i++) longStall.frame(anchor + 14_000_000_000L + (long)i * 1_000_000_000L);
        BenchmarkRecorder.Result stallReport = BenchmarkRecorder.analyze(longStall.take());
        check(stallReport != null && stallReport.over50ms() >= 1, "multi-second stalls counted, not discarded");
        check(stallReport.p99Ms() >= 500, "stalls included in 99th percentile");
        Path output = BenchmarkRecorder.save(report, temp.resolve("recordings"));
        check(Files.exists(output), "benchmark data saved");
        FrameMetrics graphMeter = new FrameMetrics();
        for (int i = 0; i < 10; i++) graphMeter.frame(1_000_000_000L + i * 11_000_000L);
        int[] graph = graphMeter.graph(48);
        check(graph.length == 48 && graph[47] == 11 && graph[0] == 0, "bounded overlay history");
        FrameMetrics.Snapshot good = frameSeries(10);
        check(Math.abs(good.fps() - 100.0) < 0.01, "100 fps average");
        check(Math.abs(good.p95Ms() - 10.0) < 0.01, "p95 frame time");
        check(Math.abs(good.onePercentLow() - 100.0) < 0.01, "one percent low");
        FrameMetrics.Snapshot bad = frameSeries(35);
        FrameMetrics frozen = new FrameMetrics();
        frozen.frame(1_000_000_000L);
        frozen.frame(1_400_000_000L);
        check(frozen.snapshot().longFrames() == 1 && frozen.snapshot().p99Ms() > 350,
                "real 400 ms stutter is recorded, not hidden");
        long start = 2_000_000_000L;
        for (int i = 0; i < 2500; i++) {
            frozen.frame(start);
            start += 10_000_000L;
        }
        check(frozen.snapshot().longFrames() == 0, "spike count uses bounded window");
        ParticleBudget budget = new ParticleBudget();
        check(budget.accept(true, 2), "particle first");
        check(budget.accept(true, 2), "particle second");
        check(!budget.accept(true, 2), "particle rejected");
        budget.newTick();
        check(budget.accept(true, 2), "particle budget reset");
        check(budget.accept(false, 0), "disabled particle budget");
        ParticlePressureGovernor governor = new ParticlePressureGovernor();
        for (int i = 0; i < 10; i++) {
            check(governor.update(200, 120, true, bad, 0) == 200,
                    "no degradation without particle activity");
        }
        int low = 200;
        for (int i = 0; i < 10; i++) low = governor.update(200, 120, true, bad, 80);
        check(low < 200 && low >= 50, "particle-only backpressure");
        for (int i = 0; i < 50; i++) low = governor.update(200, 120, true, good, 0);
        check(low == 200, "recovery with hysteresis");
        check(governor.update(200, 120, false, bad, 80) == 200,
                "disabled adaptation always restores max budget");
        check(!CanopyOcclusion.hide(0, true, true, true), "quality mode preserves vanilla foliage");
        check(!CanopyOcclusion.hide(1, true, true, false), "inner-only culling preserves canopy shell");
        check(CanopyOcclusion.hide(1, true, false, true), "fully surrounded leaves cull internal meshes");
        check(CanopyOcclusion.hide(2, true, true, false), "aggressive mode culls shared faces");
        check(!CanopyOcclusion.hide(2, false, true, true), "nonleaf blocks never culled");
        // Hysteretic render-thread workload controller; no world distance changes.
        RenderBudgetGovernor pacing = new RenderBudgetGovernor();
        check(pacing.decide(2000, 0).microseconds() == 0, "idle upload consumes no CPU budget");
        check(pacing.decide(2000, 1).microseconds() == 2000, "default upload allowance");
        for (int i = 0; i < 24; i++) pacing.observe(45.0, 120);
        check(pacing.decide(2000, 1).underPressure(), "sustained P95 slow frame detected");
        check(pacing.decide(2000, 1).microseconds() < 2000, "overloaded scene reduces upload work");
        var largeQueue = pacing.decide(2000, 512);
        check(largeQueue.microseconds() <= 5000 && largeQueue.minTasks() >= 4,
                "large queue receives bounded relief, no unbounded GPU upload burst");
        for (int i = 0; i < 40; i++) pacing.observe(5.0, 120);
        check(!pacing.decide(2000, 1).underPressure(), "smooth frames restore full budget");
        pacing.observe(Double.NaN, 120);
        pacing.observe(Double.POSITIVE_INFINITY, 120);
        check(!pacing.decide(2000, 1).underPressure(), "unreliable readings ignored");
        pacing.reset();
        check(pacing.decide(2000, 1).microseconds() == 2000, "governor reset");
        // Reciprocal opaque face memo: precise direction, world and section scope.
        Object snapshot = new Object();
        ReciprocalFaceCache.beginSection();
        check(!ReciprocalFaceCache.check(snapshot, 100L, 4), "uncached face misses");
        ReciprocalFaceCache.rememberPair(snapshot, 100L, 4, 101L, 5);
        check(ReciprocalFaceCache.check(snapshot, 100L, 4), "original face cached");
        check(ReciprocalFaceCache.check(snapshot, 101L, 5), "reciprocal face cached");
        check(!ReciprocalFaceCache.check(snapshot, 101L, 4), "direction is part of key");
        check(!ReciprocalFaceCache.check(new Object(), 101L, 5), "view identity must match");
        ReciprocalFaceCache.endSection();
        check(!ReciprocalFaceCache.check(snapshot, 100L, 4), "no cache access outside section");
        ReciprocalFaceCache.beginSection();
        check(!ReciprocalFaceCache.check(snapshot, 100L, 4), "old section result never reused");
        ReciprocalFaceCache.rememberPair(snapshot, 150L, 1, 151L, 0);
        ReciprocalFaceCache.beginSection();
        check(ReciprocalFaceCache.check(snapshot, 151L, 0), "nested compilation preserves active scope");
        ReciprocalFaceCache.endSection();
        ReciprocalFaceCache.endSection();
        check(ReciprocalFaceCache.hits() >= 3, "reciprocal lookup records hits");
        check(ReciprocalFaceCache.stores() >= 4, "reciprocal pairs stored");
        // Own render-thread upload pacer: tasks are never dropped or reordered.
        UploadPacer pacer = new UploadPacer();
        Queue<Runnable> q = new ArrayDeque<>();
        List<Integer> executed = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int index = i;
            q.add(() -> executed.add(index));
        }
        int passes = 0;
        while (!q.isEmpty() && passes++ < 200) {
            int count = pacer.drain(q, 500, 3);
            check(count >= 1 && count <= 8, "pacer bounded work and assured progress");
        }
        check(q.isEmpty() && passes < 200 && executed.size() == 100, "pacer drains backlog");
        for (int i = 0; i < 100; i++) check(executed.get(i) == i, "FIFO mesh uploads");
        check(pacer.stats().completed() == 100, "upload completed accounting");
        check(pacer.stats().limitedFrames() > 0, "upload limitation detected");
        check(pacer.drain(q, 500, 3) == 0 && pacer.stats().lastPending() == 0,
                "empty upload queue does not schedule work");
        pacer.resetCounters();
        check(pacer.stats().completed() == 0 && pacer.stats().limitedFrames() == 0,
                "telemetry reset independent of work queue");
        // 128-way allocation-free section tint reuse, with exact identity + position + index keys.
        SectionTintCache tint = new SectionTintCache();
        Object state = new Object(), view = new Object(), otherState = new Object();
        tint.beginSection();
        check(!tint.find(state, view, 2L, 0), "first tint lookup misses");
        tint.store(state, view, 2L, 0, -1); // -1 is a legitimate, cached color.
        check(tint.find(state, view, 2L, 0) && tint.result(state, view, 2L, 0) == -1,
                "white tint cached without sentinel collision");
        check(!tint.find(otherState, view, 2L, 0), "block-state identity matters");
        check(!tint.find(state, new Object(), 2L, 0), "snapshot/world identity matters");
        check(!tint.find(state, view, 3L, 0), "world position matters");
        check(!tint.find(state, view, 2L, 1), "tint index matters");
        tint.endSection();
        check(!tint.isActive(), "color reuse stops immediately after compile");
        for (String field : new String[] {"states", "views"}) {
            var reflect = SectionTintCache.class.getDeclaredField(field);
            reflect.setAccessible(true);
            for (Object retained : (Object[]) reflect.get(tint)) {
                check(retained == null, "ended compilation must release old world/snapshot objects");
            }
        }
        tint.beginSection();
        check(!tint.find(state, view, 2L, 0), "new section invalidates all prior colors");
        tint.beginSection();
        tint.store(state, view, 9L, 0, 0);
        tint.endSection();
        check(tint.find(state, view, 9L, 0), "nested compilation scope does not expire early");
        tint.endSection();
        check(tint.counters().hits() >= 2, "tint cache counters updated");
        try { tint.endSection(); throw new AssertionError("unbalanced scope accepted"); }
        catch (IllegalStateException expected) { }
        // GPU region allocator infrastructure: exact byte accounting, holes, reuse and handle safety.
        RegionArena arena = new RegionArena(1024);
        RegionArena.Allocation a = arena.allocate(100, 16);
        RegionArena.Allocation b = arena.allocate(200, 64);
        RegionArena.Allocation c = arena.allocate(200, 128);
        check(a != null && b != null && c != null, "region allocations fit");
        check(a.offset() % 16 == 0 && b.offset() % 64 == 0 && c.offset() % 128 == 0,
                "arena honors alignment");
        arena.verify();
        RegionArena.Allocation forged = new RegionArena.Allocation(b.id(), b.offset() + 1,
                b.length(), b.capacity());
        try { arena.free(forged); throw new AssertionError("forged handle accepted"); }
        catch (IllegalStateException expected) { }
        check(arena.isLive(b), "forged handle must never invalidate legitimate GPU allocation");
        arena.verify();
        arena.free(b);
        arena.verify();
        check(!arena.isLive(b), "freed region handle is invalid");
        try { arena.free(b); throw new AssertionError("double free accepted"); }
        catch (IllegalStateException expected) { }
        RegionArena.Allocation d = arena.allocate(64, 64);
        check(d != null && d.id() != b.id() && arena.isLive(d), "region reused with fresh ID");
        arena.verify();
        arena.free(a); arena.free(c); arena.free(d);
        check(arena.stats().largestFree() == 1024 && arena.stats().freeSegments() == 1,
                "region fully coalesces after free");
        arena.verify();
        java.util.Random random = new java.util.Random(1203123);
        java.util.List<RegionArena.Allocation> live = new java.util.ArrayList<>();
        RegionArena stress = new RegionArena(1 << 17);
        for (int k = 0; k < 15_000; k++) {
            if (!live.isEmpty() && random.nextBoolean()) {
                int index = random.nextInt(live.size());
                stress.free(live.remove(index));
            } else {
                RegionArena.Allocation r = stress.allocate(random.nextInt(512) + 1,
                        1 << random.nextInt(7));
                if (r != null) live.add(r);
            }
            if (k % 100 == 0) stress.verify();
        }
        for (RegionArena.Allocation item : live) stress.free(item);
        stress.verify();
        check(stress.stats().free() == 1 << 17 && stress.stats().allocations() == 0,
                "region stress restored full storage with no leaked allocations");
        System.out.println("PASS: native video settings preserved, 60s benchmark, HUD, meshing cache, allocation safety");
    }
}
