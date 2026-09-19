package dev.mopiux.atmosia.core;

/**
 * Elige nivel de detalle y distancia maxima (Secciones 6.2 y 7).
 *
 * La distancia de nubes se deriva del render distance del jugador y no de un valor fijo: generar
 * nubes mucho mas lejos de lo que el propio mundo dibuja es trabajo tirado, y quedarse corto en
 * configuraciones altas se ve peor que no tener nubes.
 */
public final class LodSelector {

    /** Umbrales orientativos de la Seccion 7, configurables. */
    private final double highUntil;
    private final double mediumUntil;

    /**
     * Antes existia un {@code lowUntil} separado de {@code maxDistance}, pero valian siempre lo
     * mismo: el tramo de LOW llegaba hasta el borde del domo. Eso dejaba al cuarto nivel sin tramo
     * propio y, por lo tanto, sin usarse nunca. Se eliminaron los dos.
     */
    private final double maxDistance;

    /** Nivel mas detallado que este selector puede devolver, venga la distancia que venga. */
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
     * @param renderDistanceChunks el valor de la configuracion de Minecraft
     * @param multiplier           multiplicador configurable sobre esa distancia
     */
    public static LodSelector forRenderDistance(int renderDistanceChunks, double multiplier) {
        return forRenderDistance(renderDistanceChunks, multiplier, LodLevel.HIGH);
    }

    /**
     * Igual, pero con un tope de detalle: lo que impone el perfil grafico.
     *
     * El tope no acorta el domo ni cambia el tamano de celda. Solo impide que las regiones cercanas
     * usen el nivel mas caro, que es donde esta el relleno.
     *
     * @param detailCap nivel mas detallado permitido
     */
    public static LodSelector forRenderDistance(int renderDistanceChunks, double multiplier,
                                                LodLevel detailCap) {
        double worldDistance = renderDistanceChunks * 16.0D;
        // El piso es generoso a proposito: un domo de nubes corto se nota muchisimo mas que uno
        // largo, porque el borde queda dentro del campo de vision y el cielo se ve recortado.
        double max = Math.max(512.0D, worldDistance * multiplier);
        // Las proporciones replican los tramos del documento (300/800/1500 sobre 1500).
        return new LodSelector(max * 0.20D, max * 0.53D, max, detailCap);
    }

    /** Escala fija, para tests y para la configuracion manual. */
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

    /**
     * Media diagonal de una region, en bloques.
     *
     * Es el margen que hay que agregarle al domo para decidir que se dibuja. El nivel de una region
     * se decide con la distancia a su CENTRO, asi que una region cuyo centro cae justo afuera del
     * domo todavia puede tener medio lado adentro. Recortarla entera deja el borde del domo
     * convertido en un poligono escalonado de 256 bloques -y eso, visto desde abajo, es una recta
     * larga en el cielo.
     */
    public static final double DRAW_MARGIN = RegionKey.REGION_SIZE * 0.708D;

    /**
     * Nivel para dibujar, con el margen del borde ya aplicado.
     *
     * Las regiones del borde se dibujan aunque su centro caiga afuera; lo que las hace desaparecer
     * es el desvanecimiento por distancia, que se aplica por fragmento y no por region.
     */
    public LodLevel levelForDrawing(double distance) {
        LodLevel level = this.levelFor(distance);
        if (level != null) {
            return level;
        }
        return distance <= this.maxDistance + DRAW_MARGIN ? this.capped(LodLevel.LOW) : null;
    }

    /** Nivel para una distancia dada, o {@code null} si esta fuera de rango y no debe existir. */
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
     * Aplica el tope del perfil. El orden del enum va del mas detallado al menos, asi que el tope
     * gana cuando el nivel que pedia la distancia es mas fino que el, y nunca al reves: un perfil
     * alto no puede forzar detalle donde la distancia no lo justifica.
     */
    private LodLevel capped(LodLevel level) {
        return level.ordinal() < this.detailCap.ordinal() ? this.detailCap : level;
    }

    /** Donde empieza a desvanecerse el borde del domo, como fraccion del alcance. */
    private static final double FADE_START = 0.25D;

    /**
     * Atenuacion por distancia en [0,1], para que el borde del mundo de nubes no aparezca como un
     * corte recto.
     *
     * <h2>Por que la banda es ancha</h2>
     *
     * Esta atenuacion se aplica una vez por region, porque cada region es un draw call y su
     * opacidad se pasa como un uniforme. Una region mide 256 bloques, asi que si la banda de
     * desvanecimiento es mas angosta que eso, dos regiones vecinas pueden quedar una entera
     * visible y la otra entera invisible: un borde recto de 256 bloques y, al moverse el jugador,
     * un parpadeo.
     *
     * Con el 40% del alcance la banda mide varias regiones -307 bloques con un domo de 768- y el
     * salto entre vecinas baja a una fraccion. Es la unica discontinuidad por region que queda en
     * el sistema, y la unica que no se puede llevar a cero sin dibujar cada corte por separado.
     *
     * El suavizado hermite ademas anula la derivada en los dos extremos, asi que ni el arranque ni
     * el final de la banda se notan como un quiebre.
     */
    public float distanceFade(double distance) {
        double fadeStart = this.maxDistance * FADE_START;
        if (distance <= fadeStart) {
            return 1.0F;
        }
        if (distance >= this.maxDistance) {
            return 0.0F;
        }
        double t = (distance - fadeStart) / (this.maxDistance - fadeStart);
        double suave = t * t * (3.0D - 2.0D * t);
        return (float) (1.0D - suave);
    }
}
