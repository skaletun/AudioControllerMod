package dev.solarvant.mixin;

import dev.solarvant.engine.FaceAccelerator;
import dev.solarvant.engine.SolarvantEngine;
import dev.solarvant.engine.ReciprocalFaceCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Early-out for two known full opaque Minecraft cubes; arbitrary mod blocks use vanilla. */
@Mixin(Block.class)
public abstract class OpaqueFaceMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void solarvant$skipProvablyHiddenFaces(BlockState current, BlockGetter level,
            BlockPos origin, Direction face, BlockPos neighbor, CallbackInfoReturnable<Boolean> result) {
        if (!SolarvantEngine.faceEnabled() || !FaceAccelerator.candidate(current)) return;
        if (ReciprocalFaceCache.check(level, origin.asLong(), face.ordinal())) {
            FaceAccelerator.recordedHide();
            result.setReturnValue(false);
            return;
        }
        BlockState adjacent = level.getBlockState(neighbor);
        if (FaceAccelerator.canHide(current, adjacent)) {
            ReciprocalFaceCache.rememberPair(level, origin.asLong(), face.ordinal(),
                    neighbor.asLong(), face.getOpposite().ordinal());
            FaceAccelerator.recordedHide();
            result.setReturnValue(false);
        }
    }
}
