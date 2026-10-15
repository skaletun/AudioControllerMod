package dev.solarvant.mixin;

import dev.solarvant.engine.TintCompilerBridge;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Same-color, same-position reuse inside one compile only; never persists. */
@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {
    @Inject(method = "getColor(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I",
            at = @At("HEAD"), cancellable = true)
    private void solarvant$readTint(BlockState state, BlockAndTintGetter view, BlockPos pos,
                                  int tint, CallbackInfoReturnable<Integer> callback) {
        if (TintCompilerBridge.cacheable(state) && TintCompilerBridge.find(state, view, pos, tint)) {
            callback.setReturnValue(TintCompilerBridge.result(state, view, pos, tint));
        }
    }

    @Inject(method = "getColor(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I)I",
            at = @At("RETURN"))
    private void solarvant$writeTint(BlockState state, BlockAndTintGetter view, BlockPos pos,
                                    int tint, CallbackInfoReturnable<Integer> callback) {
        if (TintCompilerBridge.cacheable(state))
            TintCompilerBridge.store(state, view, pos, tint, callback.getReturnValue());
    }
}
