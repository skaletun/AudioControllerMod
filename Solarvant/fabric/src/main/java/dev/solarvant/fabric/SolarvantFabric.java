package dev.solarvant.fabric;

import dev.solarvant.client.SolarvantKeybind;
import dev.solarvant.client.SolarvantOverlay;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import dev.solarvant.client.SolarvantClient;
import dev.solarvant.client.SolarvantScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;

public final class SolarvantFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        SolarvantClient.rendererName(FabricLoader.getInstance().isModLoaded("sodium") ? "Sodium" : "Vanilla");
        KeyBindingHelper.registerKeyBinding(SolarvantKeybind.OVERLAY);
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> SolarvantOverlay.draw(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            SolarvantKeybind.tick();
            SolarvantClient.tick();
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, sw, sh) -> {
            if (!(screen instanceof OptionsScreen)) return;
            Screens.getButtons(screen).add(Button.builder(
                    Component.translatable("solarvant.options.button"),
                    button -> mc.setScreen(new SolarvantScreen(screen)))
                    .bounds(8, 7, 145, 21).build());
        });
    }
}
