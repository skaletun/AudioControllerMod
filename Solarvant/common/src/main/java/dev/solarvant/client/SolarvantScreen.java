package dev.solarvant.client;

import dev.solarvant.core.FrameMetrics;
import dev.solarvant.core.Preset;
import dev.solarvant.core.SolarvantConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Pure vanilla Minecraft widgets. Solarvant never edits options.txt. */
public final class SolarvantScreen extends Screen {
    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xB0B0B0;
    private final Screen parent;
    private int tab;
    private int left;
    private int top;
    private int panelWidth;
    public SolarvantScreen(Screen parent) { super(Component.translatable("solarvant.title")); this.parent = parent; }
    private static Component tr(String id) { return Component.translatable("solarvant." + id); }
    private void button(int x, int y, int w, Component caption, Runnable callback) {
        addRenderableWidget(Button.builder(caption, b -> callback.run()).bounds(x,y,w,20).build());
    }
    private void redraw() { Minecraft.getInstance().setScreen(this); }
    private void update(Runnable task) { task.run(); SolarvantClient.updateEngine(); rebuildWidgets(); }
    @Override protected void init() {
        SolarvantClient.start();
        panelWidth = Math.min(420, Math.max(245,width - 24));
        left = (width - panelWidth)/2;
        top = Math.max(18,(height - 306)/2);
        int tw = (panelWidth - 8) / 3;
        button(left, top+27,tw,tr("tab.presets"), () -> { tab=0; rebuildWidgets(); });
        button(left+tw+4,top+27,tw,tr("tab.engine"), () -> { tab=1; rebuildWidgets(); });
        button(left+tw*2+8,top+27,tw,tr("tab.metrics"), () -> { tab=2; rebuildWidgets(); });
        int y = top + 73;
        SolarvantConfig c = SolarvantClient.settings();
        if (tab == 0) {
            Preset[] modes = {Preset.QUALITY, Preset.BALANCED, Preset.PERFORMANCE};
            String[] labels = {"preset.quality", "preset.balanced", "preset.performance"};
            for(int i=0;i<modes.length;i++) {
                final Preset choice=modes[i];
                button(left+20,y+i*33,panelWidth-40,
                       Component.literal((c.preset==choice?"> ":"")+tr(labels[i]).getString()),
                       () -> update(() -> SolarvantClient.usePreset(choice)));
            }
        } else if (tab == 1) {
            int half=(panelWidth-14)/2;
            button(left+5,y,half,tr("setting.engine"),()->update(()->c.engineEnabled=!c.engineEnabled));
            button(left+9+half,y,half,Component.literal(c.engineEnabled?"ON":"OFF"),()->update(()->c.engineEnabled=!c.engineEnabled));
            button(left+5,y+34,half,tr("setting.fastFaces"),()->update(()->c.fastOpaqueFaces=!c.fastOpaqueFaces));
            button(left+9+half,y+34,half,Component.literal(c.fastOpaqueFaces?"ON":"OFF"),()->update(()->c.fastOpaqueFaces=!c.fastOpaqueFaces));
            button(left+5,y+68,half,tr("setting.tintCache"),()->update(()->c.cacheSectionTints=!c.cacheSectionTints));
            button(left+9+half,y+68,half,Component.literal(c.cacheSectionTints?"ON":"OFF"),()->update(()->c.cacheSectionTints=!c.cacheSectionTints));
        } else {
            button(left+20,y,panelWidth-40,tr("metrics.overlay"),()->{SolarvantClient.toggleOverlay(); rebuildWidgets();});
            button(left+20,y+34,panelWidth-40,tr("metrics.mode"),()->{SolarvantClient.toggleCompactOverlay(); rebuildWidgets();});
            button(left+20,y+68,panelWidth-40,tr("benchmark.start"),()->{SolarvantClient.startBenchmark(); rebuildWidgets();});
        }
        button((width-180)/2,height-29,180,tr("done"),this::onClose);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        renderBackground(g,mx,my,delta);
        g.drawCenteredString(font,title,width/2,top+6,WHITE);
        if(tab==0) g.drawCenteredString(font,tr("preset.help"),width/2,top+189,GRAY);
        if(tab==1) {
            g.drawCenteredString(font,tr("engine.fidelity"),width/2,top+205,GRAY);
            g.drawCenteredString(font,tr("engine.vanilla"),width/2,top+222,GRAY);
        }
        if(tab==2){
            FrameMetrics.Snapshot s=SolarvantClient.metrics();
            String line=String.format(Locale.ROOT,"FPS %.0f  P95 %.1fms  P99 %.1fms",s.fps(),s.p95Ms(),s.p99Ms());
            g.drawCenteredString(font,Component.literal(line),width/2,top+198,WHITE);
            String status=SolarvantClient.benchmarkStatus();
            g.drawCenteredString(font,Component.literal(font.plainSubstrByWidth(status,panelWidth-8)),width/2,top+218,GRAY);
        }
        super.render(g,mx,my,delta);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
