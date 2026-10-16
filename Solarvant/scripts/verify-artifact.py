#!/usr/bin/env python3
"""Validate published mod JAR contents and loader-specific injection metadata."""
import json
import sys
from pathlib import Path
from zipfile import ZipFile


def verify(path: Path, loader: str) -> None:
    with ZipFile(path) as z:
        names = set(z.namelist())
        required = [
            'dev/solarvant/engine/FaceAccelerator.class',
            'dev/solarvant/engine/SectionTintCache.class',
            'dev/solarvant/engine/TintCompilerBridge.class',
            'dev/solarvant/engine/RegionArena.class',
            'dev/solarvant/engine/RegionMeshPipeline.class',
            'dev/solarvant/engine/RegionGpuBackend.class',
            'dev/solarvant/engine/QuadIndexCache.class',
            'dev/solarvant/engine/NativeRegionEngine.class',
            'dev/solarvant/engine/SolarvantVertexBuffer.class',
            'dev/solarvant/mixin/VertexBufferStateAccessor.class',
            'dev/solarvant/mixin/RenderSectionBufferMixin.class',
            'dev/solarvant/engine/SectionFrustum.class',
            'dev/solarvant/engine/ReciprocalFaceCache.class',
            'assets/solarvant/icon.png',
            'dev/solarvant/mixin/SectionCompilerMixin.class',
            'dev/solarvant/mixin/BlockColorsMixin.class',
            'dev/solarvant/client/SolarvantOverlay.class',
            'dev/solarvant/core/BenchmarkRecorder.class',
            'dev/solarvant/client/SolarvantScreen.class',
            'assets/solarvant/lang/en_us.json',
            'assets/solarvant/lang/ru_ru.json',
        ]
        missing = [name for name in required if name not in names]
        if missing:
            raise AssertionError(f'{path} missing: {missing}')
        mixin_file = f'solarvant.{loader}.mixins.json'
        if mixin_file not in names:
            raise AssertionError(f'{path}: missing {mixin_file}')
        mixin = json.loads(z.read(mixin_file))
        client = set(mixin['client'])
        assert 'SectionCompilerMixin' in client and 'BlockColorsMixin' in client, client
        assert 'RenderSectionBufferMixin' in client and 'VertexBufferStateAccessor' in client, client
        for forbidden in ['SectionUploadMixin','ParticleEngineMixin','BlockEntityRenderMixin']:
            assert forbidden not in client, f'{forbidden} should never intercept the game' 
        assert (loader != 'forge') == ('ForgeGuiOverlayMixin' not in client)
        metadata = {'fabric': 'fabric.mod.json', 'forge': 'META-INF/mods.toml',
                    'neoforge': 'META-INF/neoforge.mods.toml'}[loader]
        assert metadata in names, metadata
        if loader == 'fabric':
            manifest = json.loads(z.read(metadata))
            assert manifest['icon'] == 'assets/solarvant/icon.png'
            assert manifest['id'] == 'solarvant'
            assert manifest['version'] == '0.9.0-dev.4', manifest['version']
        if loader != 'fabric':
            assert '0.9.0-dev.4' in z.read(metadata).decode('utf-8'), f'{loader}: stale mod metadata version'
            assert 'logoFile = "assets/solarvant/icon.png"' in z.read(metadata).decode('utf-8')
        print(f'PASS {loader}: {path.name}, {len(names)} zip entries')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('usage: verify-artifact.py <jar> <fabric|neoforge|forge>')
    verify(Path(sys.argv[1]), sys.argv[2])
