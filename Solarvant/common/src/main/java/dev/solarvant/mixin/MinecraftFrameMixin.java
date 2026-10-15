package dev.solarvant.mixin;

import dev.solarvant.client.SolarvantClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void solarvant$recordFrame(boolean renderLevel, CallbackInfo ci) {
        SolarvantClient.onFrame();
    }
}
