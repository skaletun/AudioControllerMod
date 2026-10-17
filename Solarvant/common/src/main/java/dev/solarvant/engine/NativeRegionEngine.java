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
    // A separate byte budget in RegionGpuBackend limits physically allocated VRAM.
    // Virtual pages are cheap and should not force fallback at 32 chunks.
    private static final int MAX_PAGES = 512;
    private static RegionMeshPipeline pipeline;
    private static RegionGpuBackend gpu;
    private static ClientLevel level;
    private static long generation;
    private static long fallbackMeshes;
    private static long nativeUploads;
    private static long missingLayerRecoveries;
    private static long adoptedSectionBuffers;
    private static final QuadIndexCache QUAD_INDICES = new QuadIndexCache();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Solarvant/Renderer");

    public record Residency(long worldGeneration, Ticket ticket, MeshKey key, RegionMeshPipeline.PageId page,
                            int count, int firstIndex, int baseVertex, int indexBytes,
                            VertexFormat.Mode mode) { }

    private NativeRegionEngine() { }

    /** Called only when a vanilla terrain VBO is actually replaced. */
    public static void reportMissingCompiledLayer() {
        assertRenderThread();
        missingLayerRecoveries++;
        if (missingLayerRecoveries == 1 || missingLayerRecoveries % 128 == 0)
            LOGGER.warn("Compiled section had no GPU geometry; scheduled vanilla recovery: {}",
                    missingLayerRecoveries);
    }

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
        // Shared opaque index buffers are strictly canonical; an explicitly
        // indexed opaque mesh may contain a different triangle order and must
        // use the vanilla buffer path instead of being silently corrupted.
        if (!IndexStoragePolicy.canUseNative(layer, indices != null)) return null;
        if (indices == null) indices = QUAD_INDICES.quadTriangles(state.vertexCount(), stride);
        if (indices.remaining() != state.indexCount() * stride) return null;
        initialize();
        if (pipeline == null || gpu == null) return null;
        // GPU byte pressure is managed by the backend; virtual page limits
        // are checked after a reservation so free space remains reusable.
        MeshKey key = new MeshKey(section, layer);
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
        if (pipeline.stats().pages() > MAX_PAGES) {
            pipeline.abort(prepared);
            pipeline.removeKey(key);
            gpu.releasePages(pipeline.drainReclaimedPages());
            fallbackMeshes++;
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
            if (nativeUploads == 1 || nativeUploads % 4096 == 0) {
                LOGGER.info("Native resident terrain mesh uploaded: {} (GPU pages: {}, world epoch: {})",
                        nativeUploads, pipeline.stats().pages(), generation);
            }
            return new Residency(generation, ticket, key, prepared.page(), state.indexCount(),
                    prepared.indices().offset() / stride,
                    prepared.vertices().offset() / geometry.vertexStride(),
                    geometry.indexBytes(), state.mode());
        } catch (RegionGpuBackend.GpuBudgetExceeded budget) {
            // Budget exhaustion is an expected fallback condition, not a crash.
            // The old mesh is replaced through the vanilla VertexBuffer path.
            pipeline.abort(prepared);
            pipeline.removeKey(key);
            gpu.releasePages(pipeline.drainReclaimedPages());
            fallbackMeshes++;
            if (fallbackMeshes == 1 || fallbackMeshes % 2048 == 0)
                LOGGER.warn("Native GPU budget reached; using vanilla terrain upload (fallbacks: {})",
                        fallbackMeshes);
            return null;
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
                + (s.residentBytes() / 1048576) + " MiB mesh / "
                + (gpu == null ? 0 : gpu.allocatedGpuBytes() / 1048576) + " MiB GPU / "
                + nativeUploads + " uploads"
                + " / " + adoptedSectionBuffers + " section VBOs active"
                + (missingLayerRecoveries > 0 ? " / " + missingLayerRecoveries + " recoveries" : "")
                + (fallbackMeshes > 0 ? " / " + fallbackMeshes + " fallback" : "");
    }
}
