package dev.solarvant.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Modifier check occurs on Minecraft's input/game thread, not the render thread. */
public final class SolarvantKeybind {
    public static final KeyMapping OVERLAY = new KeyMapping("key.solarvant.overlay",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.solarvant");

    private SolarvantKeybind() { }

    public static void tick() {
        while (OVERLAY.consumeClick()) {
            if (Screen.hasControlDown()) SolarvantClient.toggleOverlay();
        }
    }
}
