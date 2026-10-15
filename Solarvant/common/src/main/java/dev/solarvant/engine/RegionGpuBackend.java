package dev.solarvant.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.solarvant.engine.RegionMeshPipeline.Batch;
import dev.solarvant.engine.RegionMeshPipeline.Draw;
import dev.solarvant.engine.RegionMeshPipeline.PageId;
import dev.solarvant.engine.RegionMeshPipeline.Prepared;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Independent GL region-buffer implementation. Unlike the vanilla renderer,
 * vertex and index geometry from many sections reside within one VAO/VBO pair.
 * The caller owns the terrain shader and shader-specific per-section transforms.
 *
 * GL draw submission must not be used with a vanilla section shader lacking
 * per-draw translation. MultiDraw requires a compatible shader providing a
 * draw index / base instance lookup; otherwise use drawOne with a callback
 * applying each section's model matrix before the draw call.
 *
 * This backend is intentionally NOT automatically installed in LevelRenderer
 * until model equivalence and visual tests pass. It is an implementation, not
 * an assertion that Minecraft is already rendering through these buffers.
 */
public final class RegionGpuBackend implements AutoCloseable {
    private static final int COMMAND_STRIDE = 20;
    private final Map<PageId, GpuPage> pages = new HashMap<>();
    private final int vertexPageBytes;
    private final int indexPageBytes;
    private ByteBuffer commands = ByteBuffer.allocateDirect(COMMAND_STRIDE * 128)
            .order(ByteOrder.nativeOrder());

    public interface DrawState {
        /** Bind the shader, fog/textures and per-section camera-relative transform. */
        void prepare(Draw draw);
    }

    private record GpuPage(int vertexBuffer, int indexBuffer, int vertexArray,
                           VertexFormat format, int vertexCapacity, int indexCapacity) { }

    public RegionGpuBackend(int vertexPageBytes, int indexPageBytes) {
        if (vertexPageBytes < 4096 || indexPageBytes < 4096)
            throw new IllegalArgumentException("Region GPU storage too small");
        this.vertexPageBytes = vertexPageBytes;
        this.indexPageBytes = indexPageBytes;
    }

    private static void renderThread() { RenderSystem.assertOnRenderThread(); }

    private GpuPage ensure(PageId id, VertexFormat format) {
        GpuPage existing = pages.get(id);
        if (existing != null) {
            if (!existing.format().equals(format))
                throw new IllegalStateException("Different vertex layout in the same region");
            return existing;
        }
        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();
        int ibo = GL15.glGenBuffers();
        try {
            RenderSystem.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long)vertexPageBytes, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, (long)indexPageBytes, GL15.GL_DYNAMIC_DRAW);
            format.setupBufferState();
            GpuPage page = new GpuPage(vbo, ibo, vao, format, vertexPageBytes, indexPageBytes);
            pages.put(id, page);
            return page;
        } catch (RuntimeException | Error ex) {
            GL15.glDeleteBuffers(vbo);
            GL15.glDeleteBuffers(ibo);
            GL30.glDeleteVertexArrays(vao);
            throw ex;
        } finally {
            RenderSystem.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    /** Must be called on Minecraft's render thread. Prepared offsets are exact. */
    public void upload(Prepared mesh, VertexFormat format, ByteBuffer vertices, ByteBuffer indices) {
        renderThread();
        if (vertices.remaining() != mesh.geometry().vertexBytes()
                || indices.remaining() != mesh.geometry().indexBytes()
                || format.getVertexSize() != mesh.geometry().vertexStride())
            throw new IllegalArgumentException("CPU mesh lengths/format mismatch");
        GpuPage p = ensure(mesh.page(), format);
        if ((long)mesh.vertices().offset()+vertices.remaining() > p.vertexCapacity()
                || (long)mesh.indices().offset()+indices.remaining() > p.indexCapacity())
            throw new IllegalArgumentException("GPU mesh offset outside region page");
        try {
            RenderSystem.glBindVertexArray(p.vertexArray());
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p.vertexBuffer());
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, mesh.vertices().offset(), vertices);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, p.indexBuffer());
            GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.indices().offset(), indices);
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            RenderSystem.glBindVertexArray(0);
        }
    }

    /** Safe GL3.3 path: caller updates Minecraft-compatible shader uniforms per section. */
    public int drawOneByOne(Batch batch, DrawState state) {
        renderThread();
        if (batch.draws().isEmpty()) return 0;
        GpuPage p = requirePage(batch.page());
        RenderSystem.glBindVertexArray(p.vertexArray());
        try {
            int type = batch.page().indexStride()==2 ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT;
            for (Draw d: batch.draws()) {
                if (!d.page().equals(batch.page())) throw new IllegalArgumentException("Mixed region batch");
                state.prepare(d);
                GL32.glDrawElementsBaseVertex(GL15.GL_TRIANGLES, d.count(), type,
                        (long)d.firstIndex()*batch.page().indexStride(), d.baseVertex());
            }
            return batch.draws().size();
        } finally {
            RenderSystem.glBindVertexArray(0);
        }
    }

    /**
     * Fast GL4.3 path: caller must bind its own region-aware terrain shader
     * with per-draw transforms indexed by gl_DrawID/baseInstance. Passing an
     * ordinary Minecraft shader is forbidden (its ModelViewMat is per-section).
     */
    public int drawMultiDraw(Batch batch, boolean regionAwareShaderReady) {
        renderThread();
        if (!regionAwareShaderReady) throw new IllegalStateException("No region-aware shader bound");
        if (!GL.getCapabilities().OpenGL43)
            throw new UnsupportedOperationException("GL 4.3 multi draw unavailable");
        if (!(GL.getCapabilities().OpenGL46 || GL.getCapabilities().GL_ARB_shader_draw_parameters))
            throw new UnsupportedOperationException("Region shader draw-ID extension unavailable");
        if (batch.draws().isEmpty()) return 0;
        GpuPage p = requirePage(batch.page());
        int n = batch.draws().size();
        if ((long)n * COMMAND_STRIDE > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Too many draws");
        if (commands.capacity() < n * COMMAND_STRIDE) {
            int target = Math.max(commands.capacity(), COMMAND_STRIDE);
            while (target < n * COMMAND_STRIDE) target = Math.multiplyExact(target, 2);
            commands = ByteBuffer.allocateDirect(target).order(ByteOrder.nativeOrder());
        }
        commands.clear();
        for (int idx = 0;idx < n;idx++) {
            Draw d = batch.draws().get(idx);
            if (!d.page().equals(batch.page())) throw new IllegalArgumentException("Mixed region batch");
            commands.putInt(d.count());
            commands.putInt(1);
            commands.putInt(d.firstIndex());
            commands.putInt(d.baseVertex());
            commands.putInt(idx);
        }
        commands.flip();
        RenderSystem.glBindVertexArray(p.vertexArray());
        try {
            GL43.glMultiDrawElementsIndirect(GL15.GL_TRIANGLES,
                    batch.page().indexStride()==2 ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT,
                    commands, n, 0);
        } finally {
            RenderSystem.glBindVertexArray(0);
        }
        return n;
    }

    /** Draw a live page allocation using the caller's already active vanilla shader. */
    public void drawOne(PageId page, int indexCount, int firstIndex, int baseVertex) {
        renderThread();
        GpuPage p = requirePage(page);
        RenderSystem.glBindVertexArray(p.vertexArray());
        GL32.glDrawElementsBaseVertex(GL15.GL_TRIANGLES, indexCount,
                page.indexStride() == 2 ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT,
                (long)firstIndex * page.indexStride(), baseVertex);
    }

    /** Replace already resident transparent indices without reallocating vertex data. */
    public void updateIndices(PageId page, int offset, ByteBuffer indices) {
        renderThread();
        GpuPage p = requirePage(page);
        if (offset < 0 || (long)offset + indices.remaining() > p.indexCapacity())
            throw new IllegalArgumentException("Index upload outside page");
        RenderSystem.glBindVertexArray(p.vertexArray());
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, p.indexBuffer());
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offset, indices);
    }

    public void bind(PageId page) {
        renderThread();
        RenderSystem.glBindVertexArray(requirePage(page).vertexArray());
    }

    private GpuPage requirePage(PageId id) {
        GpuPage p = pages.get(id);
        if (p==null) throw new IllegalStateException("GPU page not uploaded or evicted");
        return p;
    }

    /** Reclaim only the GPU pages actually retired by the CPU arena. */
    public void releasePages(Set<PageId> retired) {
        renderThread();
        for (PageId id : retired) {
            GpuPage page = pages.remove(id);
            if (page != null) delete(page);
        }
    }

    /** Call after section evictions, resource reload or world change. */
    public void reconcile(Set<PageId> activePages) {
        renderThread();
        for (var it = pages.entrySet().iterator();it.hasNext();) {
            var entry = it.next();
            if (!activePages.contains(entry.getKey())) {
                delete(entry.getValue());
                it.remove();
            }
        }
    }

    private static void delete(GpuPage page) {
        RenderSystem.glBindVertexArray(0); // Keep Minecraft's tracked binding coherent.
        GL15.glDeleteBuffers(page.vertexBuffer());
        GL15.glDeleteBuffers(page.indexBuffer());
        GL30.glDeleteVertexArrays(page.vertexArray());
    }

    public int pageCount() { return pages.size(); }

    @Override public void close() {
        renderThread();
        for (GpuPage page:pages.values()) delete(page);
        pages.clear();
    }
}
