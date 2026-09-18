package dev.mopiux.atmosia.core;

/**
 * Elige nivel de detalle y distancia máxima (Secciones 6.2 y 7).
 *
 * La distancia de nubes se deriva del render distance del jugador y no de un valor fijo: generar
 * nubes mucho más lejos de lo que el propio mundo dibuja es trabajo tirado, y quedarse corto en
 * configuraciones altas se ve peor que no tener nubes.
 */
public final class LodSelector {

    /** Umbrales orientativos de la Sección 7, configurables. */
    private final double highUntil;
    private final double mediumUntil;
    private final double lowUntil;
    private final double maxDistance;

    private LodSelector(double highUntil, double mediumUntil, double lowUntil, double maxDistance) {
        this.highUntil = highUntil;
        this.mediumUntil = mediumUntil;
        this.lowUntil = lowUntil;
        this.maxDistance = maxDistance;
    }

    /**
     * Construye la escala a partir del render distance del jugador.
     *
     * @param renderDistanceChunks el valor de la configuración de Minecraft
     * @param multiplier           multiplicador configurable sobre esa distancia
     */
    public static LodSelector forRenderDistance(int renderDistanceChunks, double multiplier) {
        double worldDistance = renderDistanceChunks * 16.0D;
        // El piso es generoso a propósito: un domo de nubes corto se nota muchísimo más que uno
        // largo, porque el borde queda dentro del campo de visión y el cielo se ve recortado.
        double max = Math.max(512.0D, worldDistance * multiplier);
        // Las proporciones replican los tramos del documento (300/800/1500 sobre 1500).
        return new LodSelector(max * 0.20D, max * 0.53D, max, max);
    }

    /** Escala fija, para tests y para la configuración manual. */
    public static LodSelector fixed(double maxDistance) {
        return new LodSelector(maxDistance * 0.20D, maxDistance * 0.53D, maxDistance, maxDistance);
    }

    public double maxDistance() {
        return this.maxDistance;
    }

    /** Nivel para una distancia dada, o {@code null} si está fuera de rango y no debe existir. */
    public LodLevel levelFor(double distance) {
        if (distance > this.maxDistance) {
            return null;
        }
        if (distance <= this.highUntil) {
            return LodLevel.HIGH;
        }
        if (distance <= this.mediumUntil) {
            return LodLevel.MEDIUM;
        }
        if (distance <= this.lowUntil) {
            return LodLevel.LOW;
        }
        return LodLevel.MINIMAL;
    }

    /**
     * Atenuación por distancia en [0,1], para que el borde del mundo de nubes no aparezca como un
     * corte recto. Empieza a desvanecer en el último 15% del rango.
     */
    public float distanceFade(double distance) {
        double fadeStart = this.maxDistance * 0.85D;
        if (distance <= fadeStart) {
            return 1.0F;
        }
        if (distance >= this.maxDistance) {
            return 0.0F;
        }
        return (float) (1.0D - (distance - fadeStart) / (this.maxDistance - fadeStart));
    }
}
