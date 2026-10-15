package dev.solarvant.neoforge;

import dev.solarvant.client.SolarvantOverlay;
import dev.solarvant.client.SolarvantKeybind;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import dev.solarvant.client.SolarvantClient;
import dev.solarvant.client.SolarvantScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "solarvant", dist = Dist.CLIENT)
public final class SolarvantNeoForge {
    public SolarvantNeoForge(IEventBus modBus) {
        modBus.addListener(this::registerKeys);
        SolarvantClient.rendererName(ModList.get().isLoaded("sodium") ? "Sodium" : (ModList.get().isLoaded("embeddium") ? "Embeddium" : "Vanilla"));
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onHud);
    }
    private void registerKeys(RegisterKeyMappingsEvent event) { event.register(SolarvantKeybind.OVERLAY); }
    private void onTick(ClientTickEvent.Post event) {
        SolarvantKeybind.tick();
        SolarvantClient.tick();
    }
    private void onHud(RenderGuiEvent.Post event) { SolarvantOverlay.draw(event.getGuiGraphics()); }
    private void onScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen screen)) return;
        event.addListener(Button.builder(Component.translatable("solarvant.options.button"),
                button -> Minecraft.getInstance().setScreen(new SolarvantScreen(screen)))
                .bounds(8, 7, 145, 21).build());
    }
}
