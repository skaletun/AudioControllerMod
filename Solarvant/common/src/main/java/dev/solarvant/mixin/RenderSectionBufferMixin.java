package dev.solarvant.mixin;

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Replace only standard terrain layer buffers. Unknown render layers continue
 * through vanilla. This does not change render distance or rebuild schedules.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionBufferMixin {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Shadow public abstract BlockPos getOrigin();

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void solarvant$nativeSectionBuffer(RenderType type, CallbackInfoReturnable<VertexBuffer> cir) {
        if (!SolarvantClient.isNativeRendererEnabled()) return;
        var layer = NativeRegionEngine.layer(type);
        if (layer == null) return;
        BlockPos origin = this.getOrigin();
        Section section = new Section(Math.floorDiv(origin.getX(), 16),
                Math.floorDiv(origin.getY(), 16), Math.floorDiv(origin.getZ(), 16));
        VertexBuffer vbo = buffers.get(type);
        if (vbo == null) {
            vbo = new SolarvantVertexBuffer(section, layer);
            buffers.put(type, vbo);
        }
        if (vbo instanceof SolarvantVertexBuffer managed) managed.updateSection(section);
        cir.setReturnValue(vbo);
    }
}
