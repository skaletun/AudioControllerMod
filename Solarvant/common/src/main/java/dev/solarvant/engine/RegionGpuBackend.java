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
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
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
    // Actual GPU storage is committed on demand, not on virtual page creation.
    private static final long MAX_ALLOCATED_GPU_BYTES = 512L * 1024 * 1024;
    private static final int MIN_GPU_ALLOCATION = 64 * 1024;
    private long allocatedGpuBytes;

    public static final class GpuBudgetExceeded extends RuntimeException {
        public GpuBudgetExceeded(String message) { super(message); }
    }
    private final Map<PageId, GpuPage> pages = new HashMap<>();
    /** EBO bindings belong to VAOs; avoid rebinding an unchanged shared EBO per draw. */
    private final Set<PageId> sharedIndexBindings = new HashSet<>();
    private final int vertexPageBytes;
    private final int indexPageBytes;
    // Vanilla opaque quad topology can share one EBO across every GPU region.
    // Translucent meshes keep their independently sorted per-section indices.
    private final QuadIndexCache sharedQuads = new QuadIndexCache();
    private int sharedShortIndexBuffer;
    private int sharedIntIndexBuffer;
    private int sharedShortCount;
    private int sharedIntCount;
    private ByteBuffer commands = ByteBuffer.allocateDirect(COMMAND_STRIDE * 128)
            .order(ByteOrder.nativeOrder());

    public interface DrawState {
        /** Bind the shader, fog/textures and per-section camera-relative transform. */
        void prepare(Draw draw);
    }

    private static final class GpuPage {
        private int vertexBuffer;
        private int indexBuffer;
        private final int vertexArray;
        private final VertexFormat format;
        private int vertexCapacity;
        private int indexCapacity;
        GpuPage(int vertexBuffer, int indexBuffer, int vertexArray, VertexFormat format) {
            this.vertexBuffer = vertexBuffer;
            this.indexBuffer = indexBuffer;
            this.vertexArray = vertexArray;
            this.format = format;
        }
        int vertexBuffer() { return vertexBuffer; }
        int indexBuffer() { return indexBuffer; }
        int vertexArray() { return vertexArray; }
        VertexFormat format() { return format; }
        int vertexCapacity() { return vertexCapacity; }
        int indexCapacity() { return indexCapacity; }
    }

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
        boolean privateIndices = !IndexStoragePolicy.shared(id.layer());
        int ibo = privateIndices ? GL15.glGenBuffers() : 0;
        try {
            RenderSystem.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            if (privateIndices) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
            // Vertex attributes may be installed before buffer storage exists.
            format.setupBufferState();
            GpuPage page = new GpuPage(vbo, ibo, vao, format);
            pages.put(id, page);
            return page;
        } catch (RuntimeException | Error ex) {
            GL15.glDeleteBuffers(vbo);
            if (ibo != 0) GL15.glDeleteBuffers(ibo);
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
        int vertexEnd = Math.addExact(mesh.vertices().offset(), vertices.remaining());
        int indexEnd = Math.addExact(mesh.indices().offset(), indices.remaining());
        if (vertexEnd > vertexPageBytes || (p.indexBuffer() != 0 && indexEnd > indexPageBytes))
            throw new IllegalArgumentException("GPU mesh offset outside virtual region page");
        try {
            RenderSystem.glBindVertexArray(p.vertexArray());
            ensureStorage(p, vertexEnd, p.indexBuffer() == 0 ? 0 : indexEnd);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p.vertexBuffer());
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, mesh.vertices().offset(), vertices);
            if (p.indexBuffer() != 0) {
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, p.indexBuffer());
                GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.indices().offset(), indices);
            }
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            RenderSystem.glBindVertexArray(0);
        }
    }

    /**
     * Grow buffers without discarding the geometry uploaded for other sections.
     * A glBufferData call on an existing VBO would silently erase that terrain.
     */
    private void ensureStorage(GpuPage p, int verticesRequired, int indicesRequired) {
        if (verticesRequired > p.vertexCapacity()) {
            int next = grow(p.vertexCapacity(), verticesRequired, vertexPageBytes);
            p.vertexBuffer = expand(p, p.vertexBuffer(), p.vertexCapacity(), next, false);
            allocatedGpuBytes += (long)next - p.vertexCapacity();
            p.vertexCapacity = next;
        }
        if (p.indexBuffer() != 0 && indicesRequired > p.indexCapacity()) {
            int next = grow(p.indexCapacity(), indicesRequired, indexPageBytes);
            p.indexBuffer = expand(p, p.indexBuffer(), p.indexCapacity(), next, true);
            allocatedGpuBytes += (long)next - p.indexCapacity();
            p.indexCapacity = next;
        }
    }

    private static int grow(int oldCapacity, int required, int limit) {
        if (required < 0 || required > limit)
            throw new IllegalArgumentException("Invalid GPU growth");
        int target = Math.min(limit, Math.max(MIN_GPU_ALLOCATION, oldCapacity));
        while (target < required) target = (int)Math.min((long)limit, (long)target * 2);
        return target;
    }

    private int expand(GpuPage p, int oldBuffer, int oldCapacity, int target, boolean index) {
        // Growth temporarily holds both allocations: enforce the peak, not just delta.
        if (allocatedGpuBytes + target > MAX_ALLOCATED_GPU_BYTES)
            throw new GpuBudgetExceeded("Native GPU residency budget exhausted");
        int binding = index ? GL15.GL_ELEMENT_ARRAY_BUFFER : GL15.GL_ARRAY_BUFFER;
        if (oldCapacity == 0) {
            GL15.glBindBuffer(binding, oldBuffer);
            GL15.glBufferData(binding, (long)target, GL15.GL_DYNAMIC_DRAW);
            return oldBuffer;
        }
        int replacement = GL15.glGenBuffers();
        boolean installed = false;
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, replacement);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long)target, GL15.GL_DYNAMIC_DRAW);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                    0L, 0L, (long)oldCapacity);
            if (index) {
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, replacement);
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, replacement);
                p.format().setupBufferState();
            }
            installed = true;
            GL15.glDeleteBuffers(oldBuffer);
            return replacement;
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            if (!installed) GL15.glDeleteBuffers(replacement);
        }
    }

    public long allocatedGpuBytes() { return allocatedGpuBytes; }

    /** Safe GL3.3 path: caller updates Minecraft-compatible shader uniforms per section. */
    public int drawOneByOne(Batch batch, DrawState state) {
        renderThread();
        if (batch.draws().isEmpty()) return 0;
        GpuPage p = requirePage(batch.page());
        RenderSystem.glBindVertexArray(p.vertexArray());
        try {
            int type = batch.page().indexStride()==2 ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT;
            if (p.indexBuffer() == 0) {
                int max = 0;
                for (Draw draw : batch.draws()) max = Math.max(max, draw.count());
                bindSharedQuadIndices(batch.page(), max, batch.page().indexStride());
            }
            for (Draw d: batch.draws()) {
                if (!d.page().equals(batch.page())) throw new IllegalArgumentException("Mixed region batch");
                state.prepare(d);
                GL32.glDrawElementsBaseVertex(GL15.GL_TRIANGLES, d.count(), type,
                        p.indexBuffer() == 0 ? 0L : (long)d.firstIndex()*batch.page().indexStride(), d.baseVertex());
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
            commands.putInt(p.indexBuffer() == 0 ? 0 : d.firstIndex());
            commands.putInt(d.baseVertex());
            commands.putInt(idx);
        }
        commands.flip();
        RenderSystem.glBindVertexArray(p.vertexArray());
        try {
            if (p.indexBuffer() == 0) {
                int max = 0;
                for (Draw draw : batch.draws()) max = Math.max(max, draw.count());
                bindSharedQuadIndices(batch.page(), max, batch.page().indexStride());
            }
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
        if (p.indexBuffer() == 0) bindSharedQuadIndices(page, indexCount, page.indexStride());
        GL32.glDrawElementsBaseVertex(GL15.GL_TRIANGLES, indexCount,
                page.indexStride() == 2 ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT,
                p.indexBuffer() == 0 ? 0L : (long)firstIndex * page.indexStride(), baseVertex);
    }

    /** Replace already resident transparent indices without reallocating vertex data. */
    public void updateIndices(PageId page, int offset, ByteBuffer indices) {
        renderThread();
        GpuPage p = requirePage(page);
        if (p.indexBuffer() == 0) throw new IllegalArgumentException("Cannot sort shared canonical opaque indices");
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

    /**
     * A reusable canonical index buffer removes one index upload per opaque
     * section and physically stores the quad triangulation just once per type.
     * Called with its target VAO already bound. Each growth only uploads the
     * new complete contiguous topology; section vertices remain untouched.
     */
    private void bindSharedQuadIndices(PageId page, int count, int stride) {
        if (count < 0 || count % 6 != 0 || (stride != 2 && stride != 4))
            throw new IllegalArgumentException("Invalid shared triangle topology");
        int handle = stride == 2 ? sharedShortIndexBuffer : sharedIntIndexBuffer;
        if (handle == 0) {
            handle = GL15.glGenBuffers();
            if (stride == 2) sharedShortIndexBuffer = handle;
            else sharedIntIndexBuffer = handle;
        }
        int capacity = stride == 2 ? sharedShortCount : sharedIntCount;
        if (!sharedIndexBindings.contains(page) || count > capacity) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, handle);
            sharedIndexBindings.add(page);
        }
        if (count <= capacity) return;
        int target = IndexStoragePolicy.growCanonicalIndexCount(capacity, count, stride);
        int vertices = Math.multiplyExact(target / 6, 4);
        ByteBuffer data = sharedQuads.quadTriangles(vertices, stride);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        if (stride == 2) sharedShortCount = target;
        else sharedIntCount = target;
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
            sharedIndexBindings.remove(id);
            if (page != null) {
                allocatedGpuBytes -= (long)page.vertexCapacity() + page.indexCapacity();
                delete(page);
            }
        }
    }

    /** Call after section evictions, resource reload or world change. */
    public void reconcile(Set<PageId> activePages) {
        renderThread();
        for (var it = pages.entrySet().iterator();it.hasNext();) {
            var entry = it.next();
            if (!activePages.contains(entry.getKey())) {
                GpuPage stale = entry.getValue();
                allocatedGpuBytes -= (long)stale.vertexCapacity() + stale.indexCapacity();
                delete(stale);
                sharedIndexBindings.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private static void delete(GpuPage page) {
        RenderSystem.glBindVertexArray(0); // Keep Minecraft's tracked binding coherent.
        GL15.glDeleteBuffers(page.vertexBuffer());
        if (page.indexBuffer() != 0) GL15.glDeleteBuffers(page.indexBuffer());
        GL30.glDeleteVertexArrays(page.vertexArray());
    }

    public int pageCount() { return pages.size(); }

    @Override public void close() {
        renderThread();
        for (GpuPage page:pages.values()) delete(page);
        pages.clear();
        allocatedGpuBytes = 0;
        sharedIndexBindings.clear();
        if (sharedShortIndexBuffer != 0) GL15.glDeleteBuffers(sharedShortIndexBuffer);
        if (sharedIntIndexBuffer != 0) GL15.glDeleteBuffers(sharedIntIndexBuffer);
        sharedShortIndexBuffer = 0;
        sharedIntIndexBuffer = 0;
        sharedShortCount = 0;
        sharedIntCount = 0;
    }
}
