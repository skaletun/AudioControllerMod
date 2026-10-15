package dev.solarvant.client;

import dev.solarvant.core.FrameMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;


/**
 * Small, allocation-conscious native GUI. No shaders, textures, OpenGL state
 * overrides, async jobs, or dynamic chunk settings. Data is sampled outside the render path.
 */
public final class SolarvantOverlay {
    private static final int BG = 0xD0101010;
    private static final int HEADER = 0xED252525;
    private static final int BORDER = 0xFF555555;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int ACCENT = 0xFFFFD45C;
    private static final int WARNING = 0xFFFFAA00;
    private static final int GREEN = 0xFF55FF55;
    private static final int GRAPH = 0xFF777777;

    private SolarvantOverlay() { }

    public static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!SolarvantClient.isOverlayVisible() || mc.level == null || mc.options.hideGui
                || mc.screen != null) return;
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        boolean compact = screenW < 430 || screenH < 295 || SolarvantClient.settings().compactHud;
        int w = Math.min(292, screenW - 16);
        if (w < 170 || screenH < 162) return;
        int h = compact ? 147 : 309;
        int x = screenW - w - 8;
        int y = 8;
        SolarvantClient.HudText data = SolarvantClient.hudText();
        g.fill(x, y, x + w, y + h, BG);
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 26, HEADER);
        g.fill(x + 10, y + 9, x + 13, y + 18, ACCENT);
        g.drawString(mc.font, "SOLARVANT", x + 20, y + 9, TEXT, false);
        g.drawString(mc.font, "CTRL+Y", x + w - 49, y + 9, MUTED, false);
        int bx = x + 11, right = x + w - 11;
        g.drawString(mc.font, data.fpsLabel(), bx, y + 35, data.fps() >= 60 ? GREEN : WARNING, false);
        g.drawString(mc.font, "FPS", bx + mc.font.width(data.fpsLabel()) + 7, y + 37, MUTED, false);
        g.drawString(mc.font, data.lowLabel(), right - mc.font.width(data.lowLabel()), y + 38, TEXT, false);
        g.drawString(mc.font, "1% low", right - mc.font.width(data.lowLabel()) - 45, y + 38, MUTED, false);
        g.fill(bx, y + 54, right, y + 55, BORDER);
        int ty = y + 61;
        line(g, bx, right, ty, "AVG / P95 / P99", data.timingLabel(), TEXT);
        line(g, bx, right, ty + 14, "SPIKES >50ms", data.spikesLabel(), WARNING);
        line(g, bx, right, ty + 28, "RAM HEAP", data.heapLabel(), TEXT);
        line(g, bx, right, ty + 42, "CPU PROCESS", SolarvantClient.cpuSummary(), TEXT);
        if (compact) {
            g.drawString(mc.font, "ESC: Settings  |  " + SolarvantClient.rendererName(), bx, y + 128, MUTED, false);
            return;
        }
        line(g, bx, right, ty + 56, "GC / 1min", SolarvantClient.gcSummary(), TEXT);
        line(g, bx, right, ty + 70, "CHUNKS", SolarvantClient.chunkSummary(), TEXT);
        line(g, bx, right, ty + 84, "ENGINE", SolarvantClient.engineState(), ACCENT);
        line(g, bx, right, ty + 98, "GPU USAGE", "N/A (no GPU telemetry)", MUTED);
        line(g, bx, right, ty + 112, "CHUNK UPLOAD", SolarvantClient.uploadSummary(), TEXT);
        line(g, bx, right, ty + 126, "FACES CULLED", SolarvantClient.faceSummary(), TEXT);
        line(g, bx, right, ty + 140, "TINT REUSE", SolarvantClient.tintSummary(), TEXT);
        line(g, bx, right, ty + 154, "SECTION BUILDS", SolarvantClient.compilerSummary(), TEXT);
        int graphTop = y + 233;
        g.fill(bx, graphTop - 4, right, graphTop - 3, BORDER);
        g.drawString(mc.font, "FRAME TIME", bx, graphTop + 1, MUTED, false);
        g.drawString(mc.font, ">50ms spike", right - 69, graphTop + 1, WARNING, false);
        int[] bars = SolarvantClient.graphBars();
        int graphBottom = y + h - 11;
        int chartTop = graphTop + 14;
        int chartH = graphBottom - chartTop;
        if (bars != null && chartH > 8) {
            int n = bars.length;
            int available = right - bx;
            for (int i = 0; i < n; i++) {
                int barWidth = Math.max(1, available / n);
                int barX = bx + (i * available) / n;
                int time = bars[i];
                int height = Math.max(1, (int) Math.min(chartH, (double) time * chartH / 50.0));
                g.fill(barX, graphBottom - height, barX + barWidth, graphBottom,
                        time > 50 ? WARNING : time > 25 ? ACCENT : GRAPH);
            }
        }
    }

    private static void line(GuiGraphics g, int x, int right, int y, String label, String value, int color) {
        Minecraft mc = Minecraft.getInstance();
        g.drawString(mc.font, label, x, y, MUTED, false);
        int max = Math.max(68, right - x - 92);
        if (mc.font.width(value) > max) value = mc.font.plainSubstrByWidth(value, max);
        g.drawString(mc.font, value, right - mc.font.width(value), y, color, false);
    }
}
