package dev.solarvant.core;

/**
 * Render-only particle admission. The hot path allocates nothing, never mutates
 * the world and only limits newly-created cosmetic particles when enabled.
 * The number of particle attempts is also recorded so frame-time pressure is
 * not misattributed to particles when no particles are being spawned.
 */
public final class ParticleBudget {
    private int consumed;
    private int attempts;
    private double smoothedAttempts;

    public void newTick() {
        smoothedAttempts = 0.95 * smoothedAttempts + 0.05 * attempts;
        attempts = 0;
        consumed = 0;
    }

    public boolean accept(boolean enabled, int limit) {
        if (!enabled) return true;
        attempts++;
        if (consumed >= limit) return false;
        consumed++;
        return true;
    }

    public double spawnPressure() { return smoothedAttempts; }
}
