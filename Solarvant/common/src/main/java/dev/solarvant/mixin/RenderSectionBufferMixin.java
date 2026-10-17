package dev.solarvant.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.solarvant.client.SolarvantClient;
import dev.solarvant.engine.NativeRegionEngine;
import dev.solarvant.engine.RegionMeshPipeline.Section;
import dev.solarvant.engine.SolarvantVertexBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Install the same terrain VBO for both asynchronous section upload scheduling
 * and rendering. Replacing it inside getBuffer() is too late and can leave
 * freshly compiled geometry in a discarded vanilla VBO (missing terrain).
 *
 * The render section constructor is invoked by the render-world setup on the
 * GL thread. If it isn't, retain the entire vanilla section rather than
 * mutate a HashMap being accessed concurrently by builder workers.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionBufferMixin {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Shadow public abstract BlockPos getOrigin();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void solarvant$installBeforeMeshing(CallbackInfo ci) {
        if (!RenderSystem.isOnRenderThread() || !SolarvantClient.isNativeRendererEnabled()) return;

        BlockPos origin = this.getOrigin();
        Section section = new Section(Math.floorDiv(origin.getX(), 16),
                Math.floorDiv(origin.getY(), 16), Math.floorDiv(origin.getZ(), 16));
        for (RenderType type : RenderType.chunkBufferLayers()) {
            var layer = NativeRegionEngine.layer(type);
            if (layer == null) continue;
            VertexBuffer old = buffers.get(type);
            if (old instanceof SolarvantVertexBuffer) continue;
            // Constructor setup is complete but this VBO cannot yet be referenced
            // by a mesher worker or the renderer: safe single-owner handoff.
            if (old != null) old.close();
            buffers.put(type, new SolarvantVertexBuffer(section, layer));
            NativeRegionEngine.markSectionBufferAdopted();
        }
    }
}
