#!/usr/bin/env python3
"""Regression gates for GPU region ownership and 32-chunk VRAM safety.

These are source-level invariants. The in-world smoke workflow separately
compiles shaders/Java and exercises the live GPU integration.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
gpu = (root / "common/src/main/java/dev/solarvant/engine/RegionGpuBackend.java").read_text()
native = (root / "common/src/main/java/dev/solarvant/engine/NativeRegionEngine.java").read_text()
vbo = (root / "common/src/main/java/dev/solarvant/engine/SolarvantVertexBuffer.java").read_text()
section = (root / "common/src/main/java/dev/solarvant/mixin/RenderSectionBufferMixin.java").read_text()

assert "private static final long MAX_ALLOCATED_GPU_BYTES" in gpu, "Native VRAM cap removed"
assert "allocatedGpuBytes + target > MAX_ALLOCATED_GPU_BYTES" in gpu, "Peak VRAM growth unbounded"
assert "GL31.glCopyBufferSubData" in gpu, "GPU page expansion would erase existing sections"
assert "ensureStorage(p, vertexEnd" in gpu, "GPU pages must grow on-demand"
ensure = gpu.split("private GpuPage ensure(", 1)[1].split("public void upload(", 1)[0]
assert "glBufferData(" not in ensure, "Virtual GPU pages must not allocate full VRAM immediately"
assert "allocatedGpuBytes -=" in gpu, "Freed pages must return VRAM accounting"
assert "pipeline.stats().pages() > MAX_PAGES" in native, "Temporary rebuilds can exceed max pages"
assert "catch (RegionGpuBackend.GpuBudgetExceeded" in native, "VRAM limit must use vanilla fallback"
assert "pipeline.abort(prepared)" in native, "Failed uploads must free pending allocations"
assert "clearEmptyLayer()" in vbo, "Empty rebuilt layers must release residency"
assert '@Inject(method = "setCompiled"' in section, "Empty compiled layers must be retired"
print("PASS: lazy GPU storage, safe growth, bounded VRAM, fallback and layer retirement")
