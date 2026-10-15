package dev.solarvant.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independent section residency and GPU draw-command planning core.
 *
 * A completed mesh is published only if its world epoch and section generation
 * are still current. Allocation and publication are atomic on the render thread.
 * No Minecraft instance, render thread API or GL object is referenced here.
 * The native GL backend must upload the mesh bytes BEFORE calling commit().
 *
 * Not yet a replacement for Minecraft's LevelRenderer: this implements the
 * ownership, visibility-plan and batch assembly side of that renderer.
 */
public final class RegionMeshPipeline {
    public enum Layer { SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT }
    public record Section(int x, int y, int z) { }
    public record MeshKey(Section section, Layer layer) {
        public MeshKey { Objects.requireNonNull(section); Objects.requireNonNull(layer); }
    }
    public record Ticket(long epoch, long serial, MeshKey key) { }
    public record Geometry(int vertices, int indices, int vertexStride,
                           int indexStride, int vertexBytes, int indexBytes) {
        public Geometry {
            if (vertices <= 0 || indices <= 0 || vertexStride <= 0
                    || (indexStride != 2 && indexStride != 4)
                    || (long) vertices * vertexStride != vertexBytes
                    || (long) indices * indexStride != indexBytes)
                throw new IllegalArgumentException("Invalid mesh geometry/byte lengths");
            if (indices % 3 != 0) throw new IllegalArgumentException("Index count not triangles");
        }
    }
    public record PageId(int x, int y, int z, Layer layer, int stride, int indexStride, int serial) { }
    public record StoredMesh(Ticket ticket, Geometry geometry, PageId page,
                             RegionArena.Allocation vertices, RegionArena.Allocation indices,
                             long uploadedFrame) { }
    public record Draw(MeshKey key, PageId page, int count, int firstIndex,
                       int baseVertex, double cameraDistanceSquared) { }
    public record Batch(PageId page, List<Draw> draws) {
        public Batch { draws = List.copyOf(draws); }
    }
    public record Plan(List<Batch> batches, int visibleDraws, int testedMeshes,
                       int invisibleMeshes) {
        public Plan { batches = List.copyOf(batches); }
    }
    public record Stats(long epoch, int activeMeshes, int pendingTickets, int pages,
                        long residentBytes, long rejectedStale, long evictions) { }
    public interface Visibility {
        /** Conservative: returning false suppresses the mesh. Unknown must return true. */
        boolean visible(Section section);
    }
    private record Region(int x, int y, int z) { }
    private record PoolKey(Region region, Layer layer, int stride, int indexStride) { }
    private static final class Page {
        final PageId id;
        final RegionArena vertexArena;
        final RegionArena indexArena;
        int refs;
        Page(PageId id, int vertexPageBytes, int indexPageBytes) {
            this.id = id;
            this.vertexArena = new RegionArena(vertexPageBytes);
            this.indexArena = new RegionArena(indexPageBytes);
        }
    }
    private final int vertexPageBytes;
    private final int indexPageBytes;
    private final Map<MeshKey, Long> serials = new HashMap<>();
    private final Map<MeshKey, StoredMesh> meshes = new HashMap<>();
    private final Map<PoolKey, List<Page>> pools = new HashMap<>();
    private final Map<PageId, Page> pages = new HashMap<>();
    private final Set<Prepared> pending = new HashSet<>();
    // Incremental GPU page reclamation: no O(all pages) walk on every mesh upload.
    private final Set<PageId> reclaimedPages = new HashSet<>();
    private long epoch = 1;
    private long serial = 0;
    private int pageSerial = 1;
    private long stale;
    private long evictions;
    private long resident;

    public RegionMeshPipeline(int vertexPageBytes, int indexPageBytes) {
        if (vertexPageBytes < 4096 || indexPageBytes < 4096)
            throw new IllegalArgumentException("GPU pages smaller than 4 KiB");
        this.vertexPageBytes = vertexPageBytes;
        this.indexPageBytes = indexPageBytes;
    }

    /** Call from world unload/resource reload before new tickets are issued. */
    public void resetWorld() {
        for (Prepared prepared : List.copyOf(pending)) abort(prepared);
        for (StoredMesh mesh : List.copyOf(meshes.values())) release(mesh);
        meshes.clear();
        serials.clear();
        pools.clear();
        pages.clear();
        reclaimedPages.clear();
        resident = 0;
        ++epoch;
    }

    /** Rebuild invalidation: supersedes ALL previous tickets for this section/layer. */
    public Ticket begin(MeshKey key) {
        Objects.requireNonNull(key);
        long next = ++serial;
        serials.put(key, next);
        return new Ticket(epoch, next, key);
    }

    /** Destroy the geometry when a section leaves the render world. */
    public void removeSection(Section section) {
        Objects.requireNonNull(section);
        for (Prepared p : List.copyOf(pending)) if (p.ticket().key().section().equals(section)) abort(p);
        for (Layer layer : Layer.values()) {
            MeshKey key = new MeshKey(section, layer);
            serials.remove(key);
            StoredMesh old = meshes.remove(key);
            if (old != null) release(old);
        }
    }

    /** Evict one layer only when the owning compiled generation is still current. */
    public boolean remove(Ticket owner) {
        if (!current(owner)) return false;
        MeshKey key = owner.key();
        serials.remove(key);
        StoredMesh old = meshes.remove(key);
        if (old != null) release(old);
        return true;
    }

    public StoredMesh get(MeshKey key) { return meshes.get(key); }

    /** Drop an incompatible replacement and its previous resident mesh safely. */
    public void removeKey(MeshKey key) {
        serials.remove(key);
        StoredMesh old = meshes.remove(key);
        if (old != null) release(old);
    }

    /** Called if a successful compile contains no geometry for a layer. */
    public boolean publishEmpty(Ticket ticket) {
        if (!current(ticket)) { stale++; return false; }
        StoredMesh old = meshes.remove(ticket.key());
        if (old != null) release(old);
        serials.remove(ticket.key());
        return true;
    }

    /**
     * Reserve residency for a finished CPU mesh. The caller copies bytes into
     * allocations with a GPU backend and calls commit only after BOTH uploads
     * succeed. On failure call abort; existing committed geometry remains live.
     * Null means the requested geometry does not fit available page geometry.
     */
    public Prepared prepare(Ticket ticket, Geometry geometry) {
        Objects.requireNonNull(geometry);
        if (!current(ticket)) { stale++; return null; }
        Region region = region(ticket.key().section());
        PoolKey pool = new PoolKey(region, ticket.key().layer(), geometry.vertexStride(),
                geometry.indexStride());
        List<Page> candidates = pools.computeIfAbsent(pool, unused -> new ArrayList<>());
        for (Page page : candidates) {
            Prepared p = allocate(page, ticket, geometry);
            if (p != null) return p;
        }
        if (geometry.vertexBytes() > vertexPageBytes || geometry.indexBytes() > indexPageBytes)
            return null;
        PageId id = new PageId(region.x(), region.y(), region.z(), pool.layer(),
                pool.stride(), pool.indexStride(), pageSerial++);
        Page page = new Page(id, vertexPageBytes, indexPageBytes);
        candidates.add(page);
        pages.put(id, page);
        Prepared prepared = allocate(page, ticket, geometry);
        if (prepared == null) throw new IllegalStateException("Empty page cannot fit geometry");
        return prepared;
    }

    private Prepared allocate(Page page, Ticket ticket, Geometry geometry) {
        RegionArena.Allocation v = page.vertexArena.allocate(geometry.vertexBytes(),
                geometry.vertexStride());
        if (v == null) return null;
        RegionArena.Allocation i = page.indexArena.allocate(geometry.indexBytes(),
                geometry.indexStride());
        if (i == null) { page.vertexArena.free(v); return null; }
        ++page.refs;
        Prepared p = new Prepared(ticket, geometry, page.id, v, i);
        if (!pending.add(p)) throw new IllegalStateException("Duplicate mesh reservation");
        return p;
    }

    public record Prepared(Ticket ticket, Geometry geometry, PageId page,
                           RegionArena.Allocation vertices, RegionArena.Allocation indices) { }

    public boolean commit(Prepared p, long frame) {
        if (p == null) throw new IllegalArgumentException("Null prepared mesh");
        if (!pending.contains(p)) {
            // World unload may have already freed this worker's pending reservation.
            if (!current(p.ticket())) { stale++; return false; }
            throw new IllegalStateException("Unknown or reused prepared handle");
        }
        if (!current(p.ticket())) {
            abort(p);
            stale++;
            return false;
        }
        pending.remove(p);
        StoredMesh old = meshes.get(p.ticket().key());
        StoredMesh stored = new StoredMesh(p.ticket(), p.geometry(), p.page(),
                p.vertices(), p.indices(), frame);
        meshes.put(p.ticket().key(), stored);
        resident += (long)p.geometry().vertexBytes() + p.geometry().indexBytes();
        if (old != null) release(old);
        return true;
    }

    /** Whether a worker's completed ticket still belongs to the current world. */
    public boolean accepts(Ticket ticket) { return current(ticket); }

    public void abort(Prepared p) {
        Objects.requireNonNull(p);
        if (!pending.remove(p)) {
            if (!current(p.ticket())) return; // cancelled on world/section teardown
            throw new IllegalStateException("Unknown or consumed prepared handle");
        }
        releaseAllocation(p.page(), p.vertices(), p.indices());
    }

    private void release(StoredMesh m) {
        resident -= (long) m.geometry().vertexBytes() + m.geometry().indexBytes();
        evictions++;
        releaseAllocation(m.page(), m.vertices(), m.indices());
    }

    private void releaseAllocation(PageId pageId, RegionArena.Allocation v, RegionArena.Allocation i) {
        Page page = pages.get(pageId);
        if (page == null) throw new IllegalStateException("GPU page not found");
        page.vertexArena.free(v);
        page.indexArena.free(i);
        if (--page.refs == 0) {
            pages.remove(pageId);
            reclaimedPages.add(pageId);
            PoolKey pool = new PoolKey(new Region(pageId.x(), pageId.y(), pageId.z()),
                    pageId.layer(), pageId.stride(), pageId.indexStride());
            List<Page> list = pools.get(pool);
            if (list != null) {
                list.remove(page);
                if (list.isEmpty()) pools.remove(pool);
            }
        }
    }

    private boolean current(Ticket ticket) {
        return ticket != null && ticket.epoch() == epoch
                && Objects.equals(serials.get(ticket.key()), ticket.serial());
    }

    private static Region region(Section section) {
        return new Region(Math.floorDiv(section.x(), 8), Math.floorDiv(section.y(), 8),
                Math.floorDiv(section.z(), 8));
    }

    /**
     * No false-positive occlusion and no material reordering: translucent is
     * preserved back-to-front, and never coalesced across different sections.
     * Indirect draw command execution is a separate backend responsibility.
     */
    public Plan buildPlan(Layer layer, double cx, double cy, double cz, Visibility visibility) {
        Objects.requireNonNull(layer); Objects.requireNonNull(visibility);
        List<Draw> visible = new ArrayList<>();
        int tested = 0;
        for (StoredMesh mesh : meshes.values()) {
            if (mesh.ticket().key().layer() != layer) continue;
            tested++;
            Section section = mesh.ticket().key().section();
            if (!visibility.visible(section)) continue;
            double dx = (section.x() * 16.0 + 8.0) - cx;
            double dy = (section.y() * 16.0 + 8.0) - cy;
            double dz = (section.z() * 16.0 + 8.0) - cz;
            visible.add(new Draw(mesh.ticket().key(), mesh.page(), mesh.geometry().indices(),
                    mesh.indices().offset() / mesh.geometry().indexStride(),
                    mesh.vertices().offset() / mesh.geometry().vertexStride(),
                    dx * dx + dy * dy + dz * dz));
        }
        if (layer == Layer.TRANSLUCENT) {
            visible.sort(Comparator.comparingDouble(Draw::cameraDistanceSquared).reversed());
        } else {
            visible.sort(Comparator.comparing((Draw d) -> d.page().x())
                    .thenComparing(d -> d.page().y()).thenComparing(d -> d.page().z())
                    .thenComparingInt(d -> d.page().serial()));
        }
        List<Batch> batches = new ArrayList<>();
        if (layer == Layer.TRANSLUCENT) {
            for (Draw draw : visible) batches.add(new Batch(draw.page(), List.of(draw)));
        } else {
            int start = 0;
            while (start < visible.size()) {
                int end = start + 1;
                while (end < visible.size() && visible.get(end).page().equals(visible.get(start).page())) end++;
                batches.add(new Batch(visible.get(start).page(), visible.subList(start, end)));
                start = end;
            }
        }
        return new Plan(batches, visible.size(), tested, tested-visible.size());
    }

    public Stats stats() { return new Stats(epoch, meshes.size(), pending.size(), pages.size(), resident, stale, evictions); }

    /** Snapshot of pages which the GL backend must keep allocated; reclaim all others. */
    public Set<PageId> activePages() { return Set.copyOf(pages.keySet()); }

    /** Incremental retirement journal; one page is reported at most once. */
    public Set<PageId> drainReclaimedPages() {
        if (reclaimedPages.isEmpty()) return Set.of();
        Set<PageId> removed = Set.copyOf(reclaimedPages);
        reclaimedPages.clear();
        return removed;
    }

    public void verify() {
        long sum = 0;
        Map<PageId,Integer> refs = new HashMap<>();
        for (Page page : pages.values()) page.vertexArena.verify();
        for (Page page : pages.values()) page.indexArena.verify();
        for (StoredMesh m : meshes.values()) {
            Page page = pages.get(m.page());
            if (page == null || !page.vertexArena.isLive(m.vertices()) || !page.indexArena.isLive(m.indices()))
                throw new IllegalStateException("Dangling GPU handle");
            refs.merge(m.page(),1,Integer::sum);
            sum += (long)m.geometry().vertexBytes() + m.geometry().indexBytes();
        }
        if (sum != resident) throw new IllegalStateException("Resident accounting drift");
        for (Prepared p : pending) {
            Page page = pages.get(p.page());
            if (page == null || !page.vertexArena.isLive(p.vertices()) || !page.indexArena.isLive(p.indices()))
                throw new IllegalStateException("Dangling pending GPU reservation");
            refs.merge(p.page(), 1, Integer::sum);
        }
        for (Page p : pages.values()) {
            if (p.refs != refs.getOrDefault(p.id,0)) throw new IllegalStateException("Page accounting drift");
        }
    }
}
