# Solarvant 0.9.0-dev.4 — GPU residency integration fixes

Minecraft Java 1.21.1 · Java 21 · Fabric, NeoForge, Forge.

**Not a production release or a demonstrated Sodium competitor.** This revision
corrects major problems with the native renderer opt-in path and measurable
hitching overhead. It does not modify Minecraft's normal graphics settings.

## Independent systems in current implementation

- `SolarvantVertexBuffer` (opt-in): terrain buffer backed by Solarvant GL3.3
  region storage; other layouts and unsupported layers use vanilla buffers.
- `RegionMeshPipeline`: world-epoch and generation-aware residency; releases
  GPU pages incrementally instead of scanning the entire page set each upload.
- `RegionGpuBackend`: pooled VAO/VBO/EBO and GL3.3 draw path; GL4.3 multi-draw
  primitives exist but are not integrated into the game world shader.
- Vanilla screen widgets, a `Ctrl+Y` frame-time monitor and standalone CSV
  benchmarking with a 10-second warmup + 60-second measurement window.

## Corrected in dev.3

1. Vanilla pre-creates the per-layer `VertexBuffer` map; the previous Mixin
   replaced entries only if missing. The enabled GPU path was therefore
   generally never selected. It now replaces existing vanilla buffers on the
   render thread and exposes an adoption counter in `Ctrl+Y`.
2. GPU retirements are collected incrementally, removing the full `Set.copyOf`
   of all active region pages on every mesh upload.
3. Benchmark captures no longer discard frames longer than 2 seconds.
   The reported 1% low is the actual average FPS of the slowest 1% frames,
   rather than `1000 / P99`.
4. `scripts/compare-benchmarks.py` compares at least three captures per
   renderer. Passing one scene does not establish general Sodium superiority.

## Build and testing

Use JDK 21 / Gradle 8.14.3. Run `bash scripts/test-core.sh` and
`python3 scripts/test_benchmarks.py`, then `gradle build` in each of the
`fabric`, `neoforge` and `forge` directories.

**The native renderer is disabled by default until it passes real-world
visual equivalence and GPU performance tests.** Do not treat a green Gradle
build or virtual-display client startup as a render-world benchmark.
