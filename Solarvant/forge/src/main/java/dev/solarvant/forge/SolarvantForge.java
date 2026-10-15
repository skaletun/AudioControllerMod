package dev.solarvant.forge;

import dev.solarvant.client.SolarvantKeybind;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import dev.solarvant.client.SolarvantClient;
import dev.solarvant.client.SolarvantScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;

@Mod("solarvant")
public final class SolarvantForge {
    public SolarvantForge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerKeys);
        SolarvantClient.rendererName(ModList.get().isLoaded("embeddium") ? "Embeddium" : "Vanilla");
        MinecraftForge.EVENT_BUS.addListener(this::onTick);
        MinecraftForge.EVENT_BUS.addListener(this::onScreen);
    }
    private void registerKeys(RegisterKeyMappingsEvent event) { event.register(SolarvantKeybind.OVERLAY); }
    private void onTick(TickEvent.ClientTickEvent.Post event) {
        SolarvantKeybind.tick();
        SolarvantClient.tick();
    }
    private void onScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen screen)) return;
        event.addListener(Button.builder(Component.translatable("solarvant.options.button"),
                button -> Minecraft.getInstance().setScreen(new SolarvantScreen(screen)))
                .bounds(8, 7, 145, 21).build());
    }
}
