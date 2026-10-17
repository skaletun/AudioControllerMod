# Solarvant rendering release gates (Minecraft 1.21.1)

The release is blocked unless ALL criteria pass for Fabric, NeoForge and Forge:

1. Client startup without missing classes, metadata or Mixin failures.
2. Create and stay in an actual 8-chunk and 32-chunk world with the native renderer ON, confirm native mesh residency in the log and no fatal exceptions.
3. Compare deterministic snapshots of the exact same camera/world with native renderer OFF vs ON: no missing terrain, incorrect lightmap, vertex-format corruption, holes, or transparent-sort regression. Disable progress overlays and wait for chunk loading to settle before capturing.
4. Test normal-world operation: moving across chunk boundaries, teleporting, relogging, switching dimensions, F3+T resource reload, changing worlds and closing the client. Verify all GPU allocations get freed and stale compile results cannot resurrect previous sections.
5. Render on hardware OpenGL 3.3 and 4.3 drivers (NVIDIA, AMD, Intel). Compare full frame output and diagnostic GL errors.
6. Benchmark identical 8/16/32 chunk camera paths on Ryzen 5 5600 + RTX 4060 and other hardware, three trials per configuration, Sodium/vanilla/Solarvant at same visuals and JVM heap. Report median average FPS, actual 1% low, P99, 50ms spikes, GPU/CPU timings, heap and VRAM. Do not claim "faster than Sodium" unless the data support it.
7. Independent source and artifact audits and signed/traceable release with SHA-256 checksums and recorded commit SHA.

A green compile CI or first-frame screenshot is insufficient for a stable release. Native VBOs are constructed with the render sections before asynchronous compilation to prevent a worker-upload/render-object mismatch. Do not reinstate the getBuffer-time VBO replacement.
