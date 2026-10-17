#!/usr/bin/env python3
"""Regression guard: getBuffer must never swap VBO identity after meshing."""
from pathlib import Path
source = (Path(__file__).resolve().parents[1] /
          'common/src/main/java/dev/solarvant/mixin/RenderSectionBufferMixin.java').read_text()
assert '@Inject(method = "<init>"' in source, 'VBO installation must occur in section constructor'
assert 'SolarvantVertexBuffer(section, layer)' in source, 'No native VBO adoption in constructor'
assert 'RenderSystem.isOnRenderThread()' in source, 'GL access must be render-thread guarded'
assert '@Inject(method = "getBuffer"' not in source, 'Late getBuffer replacement reintroduces missing terrain'
print('PASS: section buffer adoption precedes asynchronous meshing')
