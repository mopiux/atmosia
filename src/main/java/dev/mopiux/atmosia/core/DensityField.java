package dev.mopiux.atmosia.core;

/**
 * Convierte ruido en densidad de nube y decide qué se dibuja en cada slice.
 *
 * Es el corazón de la representación elegida en la Fase 0: en vez de construir una forma
 * tridimensional, se corta la capa en slices horizontales y cada slice usa un umbral de densidad
 * distinto. Umbrales altos arriba y abajo, bajos en el medio, producen una silueta redondeada —el
 * perfil vertical que hace que se lea como nube y no como una lámina (Sección 11.2).
 *
 * Sin ray marching y sin compute shaders, como exige el documento: son funciones sobre la densidad
 * que el sistema ya tiene.
 */
public final class DensityField {

    /** Ancho de la transición entre "no hay nube" y "nube opaca". Más bajo, bordes más duros. */
    private static final double EDGE_SOFTNESS = 0.22D;

    /** Cuánto oscurece la base de la capa respecto del techo. */
    private static final float BOTTOM_SHADE = 0.62F;

    /**
     * Cobertura máxima admitida. Con cobertura 1.0 no queda un solo punto del cielo por debajo del
     * umbral y el resultado es una losa uniforme de horizonte a horizonte, que no es "muchas nubes"
     * sino ninguna: sin huecos no hay formas que mirar.
     */
    private static final double MAX_COVERAGE = 0.95D;

    private final NoiseField noise;
    private final CloudLayerDef layer;
    private final double coverageScale;

    public DensityField(NoiseField noise, CloudLayerDef layer) {
        this(noise, layer, 1.0D);
    }

    /**
     * @param coverageScale multiplicador de cobertura del jugador. 1.0 deja la capa como fue
     *                      diseñada; más alto agranda las formaciones, más bajo despeja el cielo.
     */
    public DensityField(NoiseField noise, CloudLayerDef layer, double coverageScale) {
        this.noise = noise;
        this.layer = layer;
        this.coverageScale = coverageScale;
    }

    public CloudLayerDef layer() {
        return this.layer;
    }

    /** Cobertura de la capa con el ajuste del jugador aplicado y acotada a un rango con sentido. */
    public double effectiveCoverage() {
        double scaled = this.layer.coverage() * this.coverageScale;
        return Math.max(0.0D, Math.min(MAX_COVERAGE, scaled));
    }

    /**
     * Densidad en [0,1] para un punto en espacio de nube.
     *
     * La cobertura desplaza el umbral en vez de escalar el resultado: así subirla agranda las
     * formaciones existentes en lugar de volver todo el cielo uniformemente más opaco.
     */
    public double densityAt(double cloudX, double cloudZ) {
        double raw = this.noise.fbm(cloudX / this.layer.noiseScale(), cloudZ / this.layer.noiseScale());
        double floor = 1.0D - this.effectiveCoverage();
        if (raw <= floor) {
            return 0.0D;
        }
        return Math.min(1.0D, (raw - floor) / Math.max(1.0E-6D, 1.0D - floor));
    }

    /**
     * Umbral del slice {@code index} de {@code slices}.
     *
     * El slice del medio acepta casi cualquier densidad; los de los extremos exigen densidad alta,
     * y por eso solo el núcleo de una formación llega arriba y abajo.
     */
    public double sliceThreshold(int index, int slices) {
        if (slices <= 1) {
            // Con un solo slice no hay perfil que construir: se dibuja el cuerpo de la nube.
            return 0.18D;
        }
        double t = (index + 0.5D) / slices;
        double fromCenter = Math.abs(t - 0.5D) * 2.0D;
        return 0.06D + 0.62D * Math.pow(fromCenter, 1.6D);
    }

    /** Altura del slice, en bloques. */
    public double sliceHeight(int index, int slices) {
        double t = slices <= 1 ? 0.5D : (index + 0.5D) / slices;
        return this.layer.baseHeight() + t * this.layer.thickness();
    }

    /**
     * Opacidad de una celda en un slice, en [0,1]. Cero significa que no se emite geometría.
     *
     * La transición suave en el borde es lo que evita el aspecto de bloques que el documento
     * descarta explícitamente (Sección 12).
     */
    public float cellAlpha(double density, double threshold) {
        if (density <= threshold) {
            return 0.0F;
        }
        double over = (density - threshold) / EDGE_SOFTNESS;
        return (float) Math.min(1.0D, over);
    }

    /**
     * Sombreado propio de la celda, en [0,1], como multiplicador del color de la capa.
     *
     * Dos términos, los dos baratos: los slices bajos reciben menos luz que los altos, y las zonas
     * más densas se oscurecen un poco más. Es una aproximación de la luz que se pierde atravesando
     * la nube, no un cálculo físico, y alcanza para que deje de leerse como una pared plana.
     */
    public float shade(double density, int sliceIndex, int slices) {
        float vertical = slices <= 1
                ? 0.88F
                : BOTTOM_SHADE + (1.0F - BOTTOM_SHADE) * ((float) sliceIndex / (slices - 1));
        float byDensity = 1.0F - 0.12F * (float) density;
        return vertical * byDensity;
    }

    /**
     * Brillo extra por dispersión hacia adelante: el borde luminoso de las nubes a contraluz
     * (Sección 11.2).
     *
     * @param sunDotView producto punto entre la dirección al sol y la dirección de vista, -1..1
     * @return multiplicador, 1.0 cuando el sol no está de frente
     */
    public static float forwardScatter(double sunDotView) {
        if (sunDotView <= 0.0D) {
            return 1.0F;
        }
        // Exponente alto: el efecto aparece solo cuando se mira bastante hacia el sol.
        return 1.0F + 0.35F * (float) Math.pow(sunDotView, 6.0D);
    }
}
