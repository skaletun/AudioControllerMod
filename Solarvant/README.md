# Solarvant 0.9.0-dev.2 — native renderer development

Java 21 · Minecraft 1.21.1 · Fabric / NeoForge / Forge

**This is a DEVELOPMENT BUILD. Not yet a Sodium competitor or a complete new terrain renderer.**

## New code in this iteration

* Independent `RegionMeshPipeline`: GPU region descriptors, suballocation, rebuild generation tickets, safe async cancellation, translucent-aware draw ordering and section page batching.
* Independent `RegionGpuBackend`: OpenGL VAO/VBO/IBO region resources, byte-exact uploads, GL3.3 per-section fallback, optional GL4.3 multi-draw submission with shader-contract enforcement. Its integration into `LevelRenderer` has **not yet been performed**, so it does **not** increase in-game FPS in this release.
* `SectionFrustum`: fail-open AABB/plane section visibility.
* Extended deterministic core tests including 10,000 randomized world/section lifecycle operations and arena invariants.

Old vanilla graphics presets and chunk-distance rewrites remain removed, and the Ctrl+Y performance HUD plus 10s warmup/60s CSV benchmark remain available. Vanilla drawing remains the default for safety. No graphic settings are changed by Solarvant.

## Build

Use JDK 21 and Gradle 8.14.3. From each loader subdirectory run `gradle build`; run `bash scripts/test-core.sh` in the root for pure-Java tests. See `docs/NATIVE_RENDERER.md` for mandatory remaining renderer integration and testing gates.

**Do not interpret a green CI build as proof of performance or visual correctness.**
