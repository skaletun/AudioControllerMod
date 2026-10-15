package dev.solarvant.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.solarvant.engine.RegionMeshPipeline.Geometry;
import dev.solarvant.engine.RegionMeshPipeline.Layer;
import dev.solarvant.engine.RegionMeshPipeline.MeshKey;
import dev.solarvant.engine.RegionMeshPipeline.Prepared;
import dev.solarvant.engine.RegionMeshPipeline.Section;
import dev.solarvant.engine.RegionMeshPipeline.StoredMesh;
import dev.solarvant.engine.RegionMeshPipeline.Ticket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Render-thread section residency driver. Owns GPU regions and keeps every
 * section buffer on vanilla shaders, lightmap, and camera matrices.
 *
 * The first live integration uses glDrawElementsBaseVertex (GL 3.3). A full
 * indirect multi-draw terrain shader is separate work, not silently claimed.
 */
public final class NativeRegionEngine {
    private static final int VERTEX_PAGE = 8 * 1024 * 1024;
    private static final int INDEX_PAGE = 2 * 1024 * 1024;
    // Maximum reserved VRAM (rather than used byte count) before safe vanilla fallback.
    private static final int MAX_PAGES = 96;
    private static RegionMeshPipeline pipeline;
    private static RegionGpuBackend gpu;
    private static ClientLevel level;
    private static long generation;
    private static long fallbackMeshes;
    private static long nativeUploads;
    private static long adoptedSectionBuffers;
    private static ByteBuffer generatedIndices = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());

    public record Residency(long worldGeneration, Ticket ticket, MeshKey key, RegionMeshPipeline.PageId page,
                            int count, int firstIndex, int baseVertex, int indexBytes,
                            VertexFormat.Mode mode) { }

    private NativeRegionEngine() { }

    /** Called only when a vanilla terrain VBO is actually replaced. */
    public static void markSectionBufferAdopted() {
        assertRenderThread();
        adoptedSectionBuffers++;
    }

    public static Layer layer(RenderType type) {
        if (type == RenderType.solid()) return Layer.SOLID;
        if (type == RenderType.cutoutMipped()) return Layer.CUTOUT_MIPPED;
        if (type == RenderType.cutout()) return Layer.CUTOUT;
        if (type == RenderType.translucent()) return Layer.TRANSLUCENT;
        return null;
    }

    private static void assertRenderThread() { RenderSystem.assertOnRenderThread(); }

    private static void initialize() {
        assertRenderThread();
        ClientLevel current = Minecraft.getInstance().level;
        if (current != level) {
            reset();
            level = current;
        }
        if (current == null) return;
        if (pipeline == null) {
            pipeline = new RegionMeshPipeline(VERTEX_PAGE, INDEX_PAGE);
            gpu = new RegionGpuBackend(VERTEX_PAGE, INDEX_PAGE);
        }
    }

    public static void resetIfWorldChanges() {
        assertRenderThread();
        if (level != Minecraft.getInstance().level) {
            reset();
            level = Minecraft.getInstance().level;
        }
    }

    public static void reset() {
        assertRenderThread();
        if (gpu != null) gpu.close();
        gpu = null;
        ++generation; // Never allow old world tickets to match a newly created pipeline.
        adoptedSectionBuffers = 0;
        if (pipeline != null) pipeline.resetWorld();
        pipeline = null;
        level = null;
    }

    private static ByteBuffer makeQuadIndices(int vertices, int stride) {
        int quads = vertices / 4;
        int bytes = Math.multiplyExact(Math.multiplyExact(quads, 6), stride);
        if (generatedIndices.capacity() < bytes) {
            int capacity = generatedIndices.capacity();
            while (capacity < bytes) capacity = Math.multiplyExact(capacity, 2);
            generatedIndices = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        }
        generatedIndices.clear().limit(bytes);
        for (int q = 0; q < quads; ++q) {
            int n = q * 4;
            if (stride == 2) {
                generatedIndices.putShort((short)n).putShort((short)(n + 1))
                        .putShort((short)(n + 2)).putShort((short)(n + 2))
                        .putShort((short)(n + 3)).putShort((short)n);
            } else {
                generatedIndices.putInt(n).putInt(n + 1).putInt(n + 2)
                        .putInt(n + 2).putInt(n + 3).putInt(n);
            }
        }
        generatedIndices.flip();
        return generatedIndices;
    }

    /** Null means vanilla must upload and draw, with no partially committed mesh. */
    public static Residency tryUpload(Section section, Layer layer, MeshData mesh) {
        assertRenderThread();
        if (section == null || layer == null || Minecraft.getInstance().level == null) return null;
        MeshData.DrawState state = mesh.drawState();
        if (state.mode() != VertexFormat.Mode.QUADS || state.vertexCount() <= 0
                || state.vertexCount() % 4 != 0 || state.indexCount() != state.vertexCount() / 4 * 6)
            return null;
        ByteBuffer vertices = mesh.vertexBuffer();
        // A region page is keyed by vertex stride. Reject other vertex layouts,
        // including shader-mod changes with identical strides, to avoid VAO aliasing.
        if (vertices == null || !DefaultVertexFormat.BLOCK.equals(state.format())) return null;
        int stride = state.indexType().bytes;
        if (stride != 2 && stride != 4) return null;
        if (stride == 2 && state.vertexCount() > 65535) return null;
        ByteBuffer indices = mesh.indexBuffer();
        if (indices == null) indices = makeQuadIndices(state.vertexCount(), stride);
        if (indices.remaining() != state.indexCount() * stride) return null;
        initialize();
        if (pipeline == null || gpu == null) return null;
        // Fail over without compromising other resident sections when VRAM is constrained.
        MeshKey key = new MeshKey(section, layer);
        if (pipeline.stats().pages() >= MAX_PAGES && pipeline.get(key) == null) {
            fallbackMeshes++;
            pipeline.removeKey(key);
            gpu.releasePages(pipeline.drainReclaimedPages());
            return null;
        }
        Ticket ticket = pipeline.begin(key);
        Geometry geometry = new Geometry(state.vertexCount(), state.indexCount(),
                state.format().getVertexSize(), stride, vertices.remaining(), indices.remaining());
        Prepared prepared = pipeline.prepare(ticket, geometry);
        if (prepared == null) {
            fallbackMeshes++;
            pipeline.removeKey(key);
            gpu.releasePages(pipeline.drainReclaimedPages());
            return null;
        }
        try {
            gpu.upload(prepared, state.format(), vertices, indices);
            if (!pipeline.commit(prepared, 0)) {
                gpu.releasePages(pipeline.drainReclaimedPages());
                fallbackMeshes++;
                return null;
            }
            gpu.releasePages(pipeline.drainReclaimedPages());
            nativeUploads++;
            return new Residency(generation, ticket, key, prepared.page(), state.indexCount(),
                    prepared.indices().offset() / stride,
                    prepared.vertices().offset() / geometry.vertexStride(),
                    geometry.indexBytes(), state.mode());
        } catch (RuntimeException | Error failure) {
            // Preserve world correctness: let caller report the GL failure rather
            // than attempt to draw a partially initialized VAO.
            if (pipeline.accepts(ticket) && pipeline.get(key) != null
                    && pipeline.get(key).ticket().equals(ticket)) {
                pipeline.removeKey(key);
            } else if (pipeline.accepts(ticket)) {
                pipeline.abort(prepared);
            }
            gpu.releasePages(pipeline.drainReclaimedPages());
            fallbackMeshes++;
            throw failure;
        }
    }

    public static void replaceSortedIndices(Residency residence, ByteBuffer replacement) {
        assertRenderThread();
        if (residence == null) throw new IllegalArgumentException("Missing native geometry");
        if (residence.worldGeneration() != generation) return;
        if (replacement == null || replacement.remaining() != residence.indexBytes())
            throw new IllegalArgumentException("Translucent index size changed");
        if (pipeline == null || !pipeline.accepts(residence.ticket())) return;
        StoredMesh current = pipeline.get(residence.key());
        if (current == null || !current.ticket().equals(residence.ticket())) return;
        gpu.updateIndices(residence.page(), current.indices().offset(), replacement);
    }

    public static void bind(Residency residence) {
        assertRenderThread();
        if (residence != null && residence.worldGeneration() == generation && gpu != null) {
            BufferUploader.invalidate();
            gpu.bind(residence.page());
        }
    }

    public static void draw(Residency residence) {
        assertRenderThread();
        if (residence == null || residence.worldGeneration() != generation || gpu == null) return;
        gpu.drawOne(residence.page(), residence.count(), residence.firstIndex(), residence.baseVertex());
    }

    public static void release(Residency residence) {
        assertRenderThread();
        if (residence == null || residence.worldGeneration() != generation || pipeline == null) return;
        if (pipeline.remove(residence.ticket())) gpu.releasePages(pipeline.drainReclaimedPages());
    }

    public static String debug() {
        if (pipeline == null) return "Native GPU: waiting";
        RegionMeshPipeline.Stats s = pipeline.stats();
        return "Native GPU: " + s.activeMeshes() + " meshes / " + s.pages() + " pages / "
                + (s.residentBytes() / 1048576) + " MiB / " + nativeUploads + " uploads"
                + " / " + adoptedSectionBuffers + " section VBOs active"
                + (fallbackMeshes > 0 ? " / " + fallbackMeshes + " fallback" : "");
    }
}
