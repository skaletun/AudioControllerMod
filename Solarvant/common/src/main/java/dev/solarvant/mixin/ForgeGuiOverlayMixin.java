package dev.solarvant.mixin;

import dev.solarvant.client.SolarvantOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 52.1.0 does not provide NeoForge's RenderGuiEvent. Forge-only client overlay. */
@Mixin(Gui.class)
public abstract class ForgeGuiOverlayMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void solarvant$drawOverlay(GuiGraphics graphics, DeltaTracker tracker, CallbackInfo ci) {
        SolarvantOverlay.draw(graphics);
    }
}
