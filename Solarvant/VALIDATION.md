# Solarvant 0.6.2 validation

- Local `scripts/test-core.sh`: checks configuration persistence, P95/P99 and >50ms stalls, FIFO upload pacing, tint-cache exact keys/invalidation, nested section compilation scopes, and aligned region suballocation/free/coalescing (15,000 randomized operations).
- Gradle builds and artifact inspections run on GitHub Actions for all three loaders; their status must be checked separately.
- The integration classes `SectionCompilerMixin`, `BlockColorsMixin`, `TintCompilerBridge`, `SectionTintCache` are actual source/runtime integrations. `RegionArena` is tested infrastructure but not bound to Minecraft's GPU resources.
- Runtime shader/render correctness, CPU/GPU profiling and user hardware benchmark: NOT yet performed; no 1,000 FPS or Sodium parity claim.

0.6.2: core tests cover hysteretic upload budget, optional foliage culling decisions, and forged-handle allocator safety. Full game startup and GPU profiling still required.

0.6.2: correct client boot NPE detected by Forge smoke test.

0.7.0: Java tests cover reciprocal hidden-face caching, world identity, scope teardown, nested builds and direction equality. Native Minecraft Button screen replaced custom widgets; icon included with matching loader metadata. All new compiled client changes require GitHub CI and client smoke checks before being described as game-ready. Real in-world GPU timings and Sodium parity remain unverified.

## Dev.4 native-residency smoke gate

The 32-chunk Mesa/Xvfb world smoke must log an actual native GPU mesh upload
and remain free of render-thread exceptions. A saved `level.dat` alone is NOT
sufficient evidence of success. Native-vs-vanilla image fidelity and P99 tests
on an RTX 4060 remain blockers for a stable 1.0 release.
