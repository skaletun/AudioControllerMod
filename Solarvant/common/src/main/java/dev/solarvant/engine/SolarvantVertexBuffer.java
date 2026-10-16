package dev.solarvant.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.solarvant.client.SolarvantClient;
import dev.solarvant.mixin.VertexBufferStateAccessor;
import dev.solarvant.engine.RegionMeshPipeline.Layer;
import dev.solarvant.engine.RegionMeshPipeline.Section;

/**
 * Drop-in section buffer. Keeps vanilla ShaderInstance state and uniforms;
 * only the GPU memory placement and indexed GL draw are implemented by Solarvant.
 * Unhandled render types, OpenGL drivers and old settings retain vanilla fallback.
 */
public final class SolarvantVertexBuffer extends VertexBuffer {
    private Section section;
    private final Layer layer;
    private NativeRegionEngine.Residency residency;

    public SolarvantVertexBuffer(Section section, Layer layer) {
        super(Usage.STATIC);
        this.section = section;
        this.layer = layer;
    }

    public void updateSection(Section next) {
        if (!next.equals(section)) {
            if (residency != null) NativeRegionEngine.release(residency);
            residency = null;
            section = next;
        }
    }

    @Override public void upload(MeshData mesh) {
        // This method owns MeshData exactly as its vanilla counterpart does.
        if (!SolarvantClient.isNativeRendererEnabled()) {
            fallbackUpload(mesh);
            return;
        }
        NativeRegionEngine.Residency replacement = NativeRegionEngine.tryUpload(section, layer, mesh);
        if (replacement == null) {
            fallbackUpload(mesh);
            return;
        }
        if (residency != null) NativeRegionEngine.release(residency);
        residency = replacement;
        ((VertexBufferStateAccessor)(Object)this).solarvant$setMode(replacement.mode());
        ((VertexBufferStateAccessor)(Object)this).solarvant$setFormat(mesh.drawState().format());
        ((VertexBufferStateAccessor)(Object)this).solarvant$setIndexCount(replacement.count());
        // Vanilla's drawWithShader still configures the actual shader's sampler,
        // fog, model-view and projection uniforms, then calls virtual draw().
        mesh.close();
    }

    private void fallbackUpload(MeshData mesh) {
        if (residency != null) {
            NativeRegionEngine.release(residency);
            residency = null;
        }
        super.bind(); // Ensure super.upload cannot overwrite shared GPU pages.
        super.upload(mesh);
    }

    @Override public void uploadIndexBuffer(ByteBufferBuilder.Result result) {
        if (residency == null) {
            super.uploadIndexBuffer(result);
            return;
        }
        try {
            NativeRegionEngine.replaceSortedIndices(residency, result.byteBuffer());
        } finally {
            result.close();
        }
    }

    @Override public void bind() {
        if (residency != null) NativeRegionEngine.bind(residency);
        else super.bind();
    }

    @Override public void draw() {
        if (residency != null) NativeRegionEngine.draw(residency);
        else if (((VertexBufferStateAccessor)(Object)this).solarvant$getMode() != null) super.draw();
    }

    @Override public void close() {
        if (residency != null) NativeRegionEngine.release(residency);
        residency = null;
        super.close();
    }
}
