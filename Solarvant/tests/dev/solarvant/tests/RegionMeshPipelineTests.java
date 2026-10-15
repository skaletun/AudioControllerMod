package dev.solarvant.tests;

import dev.solarvant.engine.RegionMeshPipeline;
import dev.solarvant.engine.SectionFrustum;
import dev.solarvant.engine.RegionMeshPipeline.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Verifies the independent renderer's descriptor/residency lifecycle and draw planner. */
public final class RegionMeshPipelineTests {
    private static void yes(boolean b, String msg) {
        if (!b) throw new AssertionError(msg);
    }
    private static Geometry geom(int n, int stride) {
        return new Geometry(n * 4, n * 6, stride, 4, n * 4 * stride, n * 6 * 4);
    }
    private static void put(RegionMeshPipeline engine, MeshKey key, int quads, int stride) {
        Ticket ticket = engine.begin(key);
        Prepared p = engine.prepare(ticket, geom(quads,stride));
        yes(p != null, "Space for mesh");
        yes(engine.commit(p,0), "Successful section commit");
    }
    public static void main(String[] args) {
        RegionMeshPipeline p = new RegionMeshPipeline(1<<20, 1<<20);
        MeshKey origin = new MeshKey(new Section(0,0,0), Layer.SOLID);
        Ticket first = p.begin(origin);
        Prepared delayed = p.prepare(first,geom(9,28));
        yes(delayed != null && delayed.vertices().offset()%28==0, "Odd vertex stride supports alignment");
        Ticket next = p.begin(origin);
        yes(!p.commit(delayed,1), "Old generation upload cannot be published");
        yes(p.stats().pendingTickets()==0 && p.stats().rejectedStale()==1, "Stale upload reclaimed");
        Prepared fresh=p.prepare(next,geom(9,28));
        yes(p.commit(fresh,1), "New generation accepted");
        p.verify();
        // The old resident mesh stays drawable while a replacement rebuilds.
        Ticket rebuilding=p.begin(origin);
        yes(p.stats().activeMeshes()==1, "Stable visual mesh during an active rebuild");
        p.verify();
        Prepared replacement=p.prepare(rebuilding,geom(7,28));
        yes(replacement!=null, "Replacement reserve");
        yes(p.commit(replacement,2), "Replacement swaps atomically");
        yes(p.stats().activeMeshes()==1, "Old mesh released after swap");
        p.verify();
        try { p.abort(replacement); throw new AssertionError("Reused handle accepted"); }
        catch(IllegalStateException expected) { }
        yes(p.publishEmpty(p.begin(origin)), "Empty layer removes old mesh");
        yes(p.stats().activeMeshes()==0, "No empty layer draw");
        yes(p.stats().pages()==0 && p.stats().residentBytes()==0, "Empty region freed");
        // A world swap invalidates every outstanding compile / pending reservation.
        Ticket stale=p.begin(origin);
        Prepared waiting=p.prepare(stale,geom(3,28));
        p.resetWorld();
        yes(!p.accepts(stale), "World epoch invalidates stale result");
        yes(!p.commit(waiting, 2), "Late async upload after world swap is ignored safely");
        yes(p.stats().pages()==0 && p.stats().pendingTickets()==0, "World reset removes pending buffers");
        p.verify();
        double[] box=new double[24];
        // [-32,32] on X/Y/Z; outward boundary intersection remains visible.
        for(int axis=0;axis<3;axis++) {
            box[axis*8+axis]=1;box[axis*8+3]=32;
            box[axis*8+4+axis]=-1;box[axis*8+7]=32;
        }
        SectionFrustum frustum=new SectionFrustum(box);
        yes(frustum.visible(new Section(1,1,1)),"Boundary AABB is conservatively visible");
        yes(!frustum.visible(new Section(4,0,0)),"Outside AABB rejected");
        yes(frustum.visible(new Section(-2,-2,-2)),"Negative section on boundary retained");
        yes(!frustum.visible(new Section(-5,0,0)),"Negative section truly outside rejected");
        yes(SectionFrustum.allVisible().visible(new Section(999,0,0)),"Fail-open universal frustum");
        // Region grouping: eight sections per spatial axis, negative coordinates floor-divide.
        put(p,new MeshKey(new Section(0,0,0),Layer.SOLID),6,28);
        put(p,new MeshKey(new Section(1,0,0),Layer.SOLID),7,28);
        put(p,new MeshKey(new Section(7,0,7),Layer.SOLID),8,28);
        put(p,new MeshKey(new Section(8,0,0),Layer.SOLID),3,28);
        put(p,new MeshKey(new Section(-1,0,0),Layer.SOLID),4,28);
        Plan plan=p.buildPlan(Layer.SOLID,0,0,0,section->true);
        yes(plan.visibleDraws()==5,"All opaque sections visible");
        yes(plan.batches().size()==3,"Draws grouped into three distinct GPU regions");
        for(Batch batch:plan.batches()) {
            for(Draw draw:batch.draws()) {
                yes(batch.page().equals(draw.page()),"Each indirect batch binds one page");
                yes(draw.firstIndex()>=0 && draw.baseVertex()>=0 && draw.count()>0,"GPU offset valid");
            }
        }
        Plan hidden=p.buildPlan(Layer.SOLID,0,0,0,section->section.x()!=8);
        yes(hidden.visibleDraws()==4 && hidden.invisibleMeshes()==1,"Conservative culler excludes only specified section");
        // Translucent meshes are never coalesced into an unordered regional batch.
        put(p,new MeshKey(new Section(0,0,0),Layer.TRANSLUCENT),10,28);
        put(p,new MeshKey(new Section(3,0,0),Layer.TRANSLUCENT),10,28);
        put(p,new MeshKey(new Section(1,0,0),Layer.TRANSLUCENT),10,28);
        Plan glass=p.buildPlan(Layer.TRANSLUCENT,0,0,0,section->true);
        yes(glass.batches().size()==3,"Translucent section ordering preserved");
        yes(glass.batches().get(0).draws().get(0).key().section().x()==3,"Farthest transparent section first");
        yes(glass.batches().get(2).draws().get(0).key().section().x()==0,"Nearest transparent section last");
        p.removeSection(new Section(1,0,0));
        p.verify();
        yes(p.buildPlan(Layer.TRANSLUCENT,0,0,0,section->true).visibleDraws()==2,"Unload evicts all section layers");
        // 32-chunk workloads, random rebuild/unload/empty/cancel/world swaps.
        Random random=new Random(105087);
        for(int step=0;step<10000;step++) {
            Section sec=new Section(random.nextInt(65)-32,random.nextInt(24)-6,random.nextInt(65)-32);
            MeshKey key=new MeshKey(sec,Layer.values()[random.nextInt(Layer.values().length)]);
            int action=random.nextInt(30);
            if(action==0) {p.resetWorld();}
            else if(action==1) p.removeSection(sec);
            else {
                Ticket t=p.begin(key);
                if(action<5) p.publishEmpty(t);
                else {
                    Geometry g=geom(random.nextInt(16)+1,random.nextBoolean()?28:32);
                    Prepared h=p.prepare(t,g);
                    if(h!=null) {
                        if(action<8) p.abort(h);
                        else if(action<10) {p.begin(key);yes(!p.commit(h,step),"Cancel rejects old ticket");}
                        else p.commit(h,step);
                    }
                }
            }
            if(step%257==0) p.verify();
        }
        p.verify();
        p.resetWorld();
        yes(p.stats().residentBytes()==0 && p.stats().pages()==0, "No GPU residency leaked after stress");
        System.out.println("PASS: 10k render-world events, GPU region allocation, transparent order, stale rebuilds");
    }
}
