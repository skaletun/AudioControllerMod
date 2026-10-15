package dev.solarvant.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Only Solarvant's own algorithms, not Minecraft's already available video options. */
public final class SolarvantConfig {
    public Preset preset = Preset.PERFORMANCE;
    public boolean hudVisible;
    public boolean compactHud;
    public boolean engineEnabled = true;
    /** Opt-in until hardware screenshots and translucent sorting are validated. */
    public boolean nativeRendererEnabled = false;
    public boolean fastOpaqueFaces = true;
    public boolean cacheSectionTints = true;
    private final Path file;

    public SolarvantConfig(Path file) { this.file = file; }

    public void applyPreset(Preset p) {
        if (p == Preset.CUSTOM) return;
        preset = p;
        engineEnabled = p != Preset.QUALITY;
        fastOpaqueFaces = p == Preset.PERFORMANCE || p == Preset.EXTREME;
        cacheSectionTints = p != Preset.QUALITY;
    }

    public void load() {
        if (!Files.isRegularFile(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
            try { preset = Preset.valueOf(p.getProperty("preset", preset.name())); }
            catch (IllegalArgumentException ignored) { preset = Preset.PERFORMANCE; }
            hudVisible = Boolean.parseBoolean(p.getProperty("hudVisible", "false"));
            compactHud = Boolean.parseBoolean(p.getProperty("compactHud", "false"));
            engineEnabled = Boolean.parseBoolean(p.getProperty("engineEnabled", "true"));
            nativeRendererEnabled = Boolean.parseBoolean(p.getProperty("nativeRendererEnabled", "false"));
            fastOpaqueFaces = Boolean.parseBoolean(p.getProperty("fastOpaqueFaces", "true"));
            cacheSectionTints = Boolean.parseBoolean(p.getProperty("cacheSectionTints", "true"));
            // Previous GPU pacing, particle/foliage reduction and vanilla options ignored.
        } catch (IOException exception) {
            System.err.println("[Solarvant] Cannot read settings: " + exception);
        }
    }

    public void validate() { }

    public void save() {
        Properties p = new Properties();
        p.setProperty("preset", preset.name());
        p.setProperty("hudVisible", Boolean.toString(hudVisible));
        p.setProperty("compactHud", Boolean.toString(compactHud));
        p.setProperty("engineEnabled", Boolean.toString(engineEnabled));
        p.setProperty("nativeRendererEnabled", Boolean.toString(nativeRendererEnabled));
        p.setProperty("fastOpaqueFaces", Boolean.toString(fastOpaqueFaces));
        p.setProperty("cacheSectionTints", Boolean.toString(cacheSectionTints));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                p.store(out, "Solarvant 0.8: native video settings preserved");
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) {
            System.err.println("[Solarvant] Cannot save settings: " + exception);
        }
    }
}
