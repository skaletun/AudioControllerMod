# Solarvant 0.6.2 render and performance acceptance tests

## Important

This build is **not** yet a complete standalone GPU section renderer. It is an
independent hybrid terrain optimization path that currently uses vanilla's
vertex and draw APIs. Do not claim Sodium parity without objective measurements.

## Startup and render correctness (mandatory before release)

For **each** Forge 52.x / NeoForge 21.1.x / Fabric Loader on Minecraft 1.21.1:

1. Start a clean modpack with Solarvant only (Fabric: include Fabric API).
2. Load a fresh world and a prior world; verify the main menu, pause, F3 and the
   `Ctrl+Y` overlay. Stop, re-open; settings persist.
3. Toggle all render options without a crash. Re-enter a world or force
   `F3+A` to rebuild chunk meshes after changing `canopyCulling`.
4. In Fancy graphics inspect jungle, mangrove swamp, underwater waterlogged
   leaves, cherry grove, cave, mountains, Nether and End. Compare screenshots
   with identical camera angles to vanilla, with canopy OFF.
5. Confirm that the optional canopy modes **visibly** trade leaf transparency
   for reduced geometry. They are not quality-preserving; the default is OFF.
6. Teleport between dimensions and reload resource packs; inspect logs for
   Mixin injection failures, stale world references and GL errors.
7. Validate slow-chunk-loading fallback: no missing sections, late job updates
   or geometry that remains absent after upload scheduling catches up.

## FPS measurements

Bench at **identical** 1920×1080 view distance, simulation distance, clouds,
Fancy leaves, shader setting and seeded world. Record for 180 seconds after
world stabilizes (3 paired trials per test), comparing vanilla, Solarvant,
Sodium 1.21.1 where supported. Move over a fixed flight route for motion runs.

- Test: stationary jungle from supplied screenshot; moving jungle; deep caves;
  ocean; Nether; and fresh-chunk generation.
- Record average/median FPS, true 1% low (mean bottom 1% of samples), P95/P99
  frame times, >50 ms frame count, CPU/GPU utilization, resident memory and GC.
- Collect JFR flame graph and RenderDoc frame: total draw calls, triangles,
  world layer VBO counts and total GPU time. Do not attribute VBO batching gains
  to Solarvant until a native GPU backend is in use.
- Acceptance: no meaningful regression in Quality/Balanced, measurable gain in
  optional dense-canopy settings with declared appearance tradeoffs; no holes.

## Technical gaps to Sodium-class rendering

Still missing: persistent GL region arena ownership, material-sorted draw lists,
chunk-visibility traversal independent of vanilla, terrain translucent sort,
custom model and resource-pack compatibility, multi-draw where available,
real-world visual test and benchmarks.
