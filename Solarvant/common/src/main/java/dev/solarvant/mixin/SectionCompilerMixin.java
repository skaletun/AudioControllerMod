package dev.solarvant.mixin;

import dev.solarvant.engine.TintCompilerBridge;
import dev.solarvant.engine.ReciprocalFaceCache;
import dev.solarvant.engine.FaceAccelerator;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scope tiny tint reuse to an individual section's immutable render snapshot.
 * No distance adjustment, extra worker, chunk query, or render-thread wait.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void solarvant$startBuild(CallbackInfoReturnable<?> callback) {
        TintCompilerBridge.begin();
        ReciprocalFaceCache.beginSection();
        FaceAccelerator.beginSection();
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void solarvant$finishBuild(CallbackInfoReturnable<?> callback) {
        TintCompilerBridge.end();
        ReciprocalFaceCache.endSection();
        FaceAccelerator.endSection();
    }
}
