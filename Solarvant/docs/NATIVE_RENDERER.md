# Solarvant native renderer — implementation state (0.9.0-dev.1)

## Verified independent components

* `RegionMeshPipeline` owns immutable mesh *descriptors* and per-layer/per-format page allocators, retains the previous mesh until a replacement is committed, tracks section generations, rejects stale results, releases residency on unload/world reset, and can build opaque region batches or depth-sorted transparent section commands.
* `RegionGpuBackend` is a render-thread OpenGL implementation with a region VAO, shared VBO and element buffer for each page. It accepts the generated exact byte offsets and `VertexFormat` from the caller, uploads using `glBufferSubData`, can perform one draw per section using GL3.3 (if the caller supplies the correct per-section vanilla-compatible shader uniforms), or `glMultiDrawElementsIndirect` on GL4.3 with a correctly configured region-aware shader and `ARB_shader_draw_parameters`/GL4.6. `drawMultiDraw` refuses to execute without the caller explicitly supplying a compatible-shader readiness signal.
* `SectionFrustum` conservatively filters the section AABBs against six clip planes.
* Tests cover 10,000 section lifecycle operations, multiple layers, negative coordinates, out-of-order builds, stale async results after world swaps, evictions, odd stride alignment, transparent ordering, conservative visibility and GPU page ownership invariants.

## Missing before changing the world-renderer path

**The native GPU backend is not yet used by Minecraft's `LevelRenderer`.** The game's `SectionCompiler` and vanilla `VertexBuffer` currently remain active. The new OpenGL class being present in a compiled JAR DOES NOT mean the GPU batching mechanism is active. No average FPS or P99 improvement is attributable to this code yet.

The next integration must contain all of the following at the same time:

1. Exact, immutable CPU `MeshData` handoff including opaque/cutout/translucent layers, correct `VertexFormat`, index type and fast discard on rebuild generation mismatch. Uploads are queued to the real render thread.
2. Region translation uniform/SSBO transport and shaders which preserve the vanilla block atlas, biome tints, lightmap, ambient occlusion, fog, GL render states, normal attributes and modded model output.
3. Atomic replacement of `LevelRenderer.renderSectionLayer` for **only** supported configurations, with a debug equivalence mode and automatic fallback, without double rendering or double uploads.
4. Translucent **quad** sorting and proper index refresh (section sorting is not sufficient for fancy water/glass), block overlays, shader-mod/resource-pack compatibility, reset on resource reload, buffer reclamation and sync with world lifecycle.
5. In-game visual golden-image comparisons at all vanilla graphics settings and with modded render layers, plus representative 32-chunk world generation and stable-scene 60-second benchmarks on a Ryzen 5 5600, RTX 4060 8 GB and 16 GB RAM. Repeat 3–5 times, compare median, P95, P99, 1% lows to Vanilla AND Sodium. No silent graphic downgrade.

Do not advertise Sodium parity or call this release complete until all five gates pass. Running OpenGL only in GitHub Xvfb is not a real-world GPU performance benchmark.

## 0.9.0-dev.2: opt-in live GL 3.3 page rendering

The render section mixin now installs `SolarvantVertexBuffer` for known vanilla terrain
layers when `nativeRendererEnabled=true`. The class retains Minecraft's
`VertexBuffer.drawWithShader` uniform/fog/lightmap pipeline, but substitutes a
shared GPU page with `glDrawElementsBaseVertex`. Normal vertex formats and
unsupported layers retain vanilla fallback; translucency index-only resort is
updated in the shared page without uploading the entire vertex array. The
fallback continues to use the original game rendering path. Buffer pages are
reclaimed after section ownership ends and at world change.

This is a live **region-storage/GL3.3 rendering prototype**, not full multi-draw.
Default OFF until on-hardware visual equivalence, mixed layers, and memory
pressure tests pass. This mode must not be compared to Sodium as a released
FPS replacement yet. The GL4.3 multi-draw shader is still not integrated.

The opt-in GL3.3 prototype accepts only `DefaultVertexFormat.BLOCK`. Any custom terrain format falls back to vanilla, preventing incompatible shared VAO layouts on shader/mod packs. Enabling the mode requires a world reload; built buffers are not replaced in-flight.
