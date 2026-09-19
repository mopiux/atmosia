package dev.mopiux.atmosia.core;

/**
 * Presupuesto por frame (Seccion 8.2).
 *
 * El renderer no genera "todo lo que haga falta": genera lo que entra en el presupuesto y deja el
 * resto para los frames siguientes, respetando el orden de prioridad. Es lo que garantiza que
 * ninguna actualizacion congele el juego, ni siquiera un instante.
 */
public final class CloudBudget {

    private final int maxRegionsPerFrame;
    private final int maxQuadsPerFrame;
    private final int maxCachedRegions;

    private int regionsThisFrame;
    private int quadsThisFrame;

    public CloudBudget(int maxRegionsPerFrame, int maxQuadsPerFrame, int maxCachedRegions) {
        this.maxRegionsPerFrame = maxRegionsPerFrame;
        this.maxQuadsPerFrame = maxQuadsPerFrame;
        this.maxCachedRegions = maxCachedRegions;
    }

    public static CloudBudget defaults() {
        return new CloudBudget(2, 24_000, 192);
    }

    public int maxCachedRegions() {
        return this.maxCachedRegions;
    }

    /** Reinicia los contadores. Se llama una vez por frame, antes de consumir la cola. */
    public void beginFrame() {
        this.regionsThisFrame = 0;
        this.quadsThisFrame = 0;
    }

    /** Si todavia entra una region de {@code quads} cuadruples en este frame. */
    public boolean canGenerate(int quads) {
        return this.regionsThisFrame < this.maxRegionsPerFrame
                && this.quadsThisFrame + quads <= this.maxQuadsPerFrame;
    }

    /**
     * Si nada entra en el presupuesto pero tampoco se genero nada todavia, se admite una region.
     * Sin esto, un presupuesto de cuadruples demasiado ajustado dejaria el cielo vacio para
     * siempre en vez de llenarse despacio.
     */
    public boolean canGenerateAtLeastOne() {
        return this.regionsThisFrame == 0;
    }

    public void recordGenerated(int quads) {
        this.regionsThisFrame++;
        this.quadsThisFrame += quads;
    }

    public int regionsThisFrame() {
        return this.regionsThisFrame;
    }

    public int quadsThisFrame() {
        return this.quadsThisFrame;
    }
}
