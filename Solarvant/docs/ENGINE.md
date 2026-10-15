# Solarvant independent render backend — engineering acceptance gates

## Current
Minecraft 1.21.1 default `LevelRenderer` remains in control of section draw submission and `SectionRenderDispatcher` manages GPU uploads. Solarvant 0.8 only speeds up a subset of section build CPU operations. 

## Renderer replacement (not yet implemented)
1. Build a model-accurate immutable section snapshot and mesh builder that consumes vanilla baker / mod-provided models, tint, lighting, opaque, cutout and translucent layers without data races.
2. Own a render-world lifecycle for section invalidation, unload, world swap and resource reload; atomic generation handles discard stale builds.
3. Allocate compact per-layer vertex data in region GPU arenas and upload through a fenced staging ring (no forced stalls or fixed frame caps).
4. Implement GL3.3-compatible batched draw submission with a shader preserving vanilla lighting, textures, fog and per-section translation; only use newer extensions when supported.
5. Frustum and occlusion visibility lists independent of load distance. Use sound conservative tests and provide a correctness fallback.
6. Re-sort translucent blocks correctly as the camera moves, preserve overlays, emissives and graphics mod compatibility.
7. Verify images in water/foliage, block-entity scenes, modded materials and shader/resource packs.
8. Run 32-chunk max quality benchmarks on target Ryzen 5 5600, RTX 4060 8GB, 16GB RAM; compare 0.1% / 1% lows and P95/P99, not only headline FPS, with Vanilla and Sodium.

No component should be labeled GPU batching until it actually owns the GL buffer and section draw loop.
