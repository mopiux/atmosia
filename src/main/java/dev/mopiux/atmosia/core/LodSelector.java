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

    /**
     * Antes existía un {@code lowUntil} separado de {@code maxDistance}, pero valían siempre lo
     * mismo: el tramo de LOW llegaba hasta el borde del domo. Eso dejaba al cuarto nivel sin tramo
     * propio y, por lo tanto, sin usarse nunca. Se eliminaron los dos.
     */
    private final double maxDistance;

    /** Nivel más detallado que este selector puede devolver, venga la distancia que venga. */
    private final LodLevel detailCap;

    private LodSelector(double highUntil, double mediumUntil, double maxDistance,
                        LodLevel detailCap) {
        this.highUntil = highUntil;
        this.mediumUntil = mediumUntil;
        this.maxDistance = maxDistance;
        this.detailCap = detailCap;
    }

    /**
     * Construye la escala a partir del render distance del jugador.
     *
     * @param renderDistanceChunks el valor de la configuración de Minecraft
     * @param multiplier           multiplicador configurable sobre esa distancia
     */
    public static LodSelector forRenderDistance(int renderDistanceChunks, double multiplier) {
        return forRenderDistance(renderDistanceChunks, multiplier, LodLevel.HIGH);
    }

    /**
     * Igual, pero con un tope de detalle: lo que impone el perfil gráfico.
     *
     * El tope no acorta el domo ni cambia el tamaño de celda. Solo impide que las regiones cercanas
     * usen el nivel más caro, que es donde está el relleno.
     *
     * @param detailCap nivel más detallado permitido
     */
    public static LodSelector forRenderDistance(int renderDistanceChunks, double multiplier,
                                                LodLevel detailCap) {
        double worldDistance = renderDistanceChunks * 16.0D;
        // El piso es generoso a propósito: un domo de nubes corto se nota muchísimo más que uno
        // largo, porque el borde queda dentro del campo de visión y el cielo se ve recortado.
        double max = Math.max(512.0D, worldDistance * multiplier);
        // Las proporciones replican los tramos del documento (300/800/1500 sobre 1500).
        return new LodSelector(max * 0.20D, max * 0.53D, max, detailCap);
    }

    /** Escala fija, para tests y para la configuración manual. */
    public static LodSelector fixed(double maxDistance) {
        return fixed(maxDistance, LodLevel.HIGH);
    }

    public static LodSelector fixed(double maxDistance, LodLevel detailCap) {
        return new LodSelector(maxDistance * 0.20D, maxDistance * 0.53D, maxDistance, detailCap);
    }

    public LodLevel detailCap() {
        return this.detailCap;
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
            return this.capped(LodLevel.HIGH);
        }
        if (distance <= this.mediumUntil) {
            return this.capped(LodLevel.MEDIUM);
        }
        return this.capped(LodLevel.LOW);
    }

    /**
     * Aplica el tope del perfil. El orden del enum va del más detallado al menos, así que el tope
     * gana cuando el nivel que pedía la distancia es más fino que él, y nunca al revés: un perfil
     * alto no puede forzar detalle donde la distancia no lo justifica.
     */
    private LodLevel capped(LodLevel level) {
        return level.ordinal() < this.detailCap.ordinal() ? this.detailCap : level;
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
