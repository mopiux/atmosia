package dev.mopiux.atmosia.core;

/**
 * Convierte ruido en densidad de nube y decide que se dibuja en cada slice.
 *
 * Es el corazon de la representacion elegida en la Fase 0: en vez de construir una forma
 * tridimensional, se corta la capa en slices horizontales y cada slice usa un umbral de densidad
 * distinto. Umbrales altos arriba y abajo, bajos en el medio, producen una silueta redondeada -el
 * perfil vertical que hace que se lea como nube y no como una lamina (Seccion 11.2).
 *
 * Sin ray marching y sin compute shaders, como exige el documento: son funciones sobre la densidad
 * que el sistema ya tiene.
 */
public final class DensityField {

    /** Ancho de la transicion entre "no hay nube" y "nube opaca". Mas bajo, bordes mas duros. */
    private static final double EDGE_SOFTNESS = 0.22D;

    /**
     * Cuanto oscurece la base de la capa respecto del techo.
     *
     * Estuvo en 0,62 hasta la 0.2.1 y era demasiado. El color propio del corte mas bajo quedaba en
     * torno a 0,48 -mas oscuro que el cielo diurno sobre el que se mezcla- asi que donde se veian
     * pocos cortes superpuestos, que es el borde de toda formacion vista desde abajo, el compuesto
     * caia por debajo del brillo del cielo y se leia como una banda gris. En el nucleo, con los
     * ocho cortes, los de arriba lo compensaban y volvia a aclarar: por eso el defecto aparecia en
     * los bordes y no en el medio.
     *
     * 0,78 conserva el gradiente que hace que una nube se lea como nube -la base mas oscura que el
     * techo- sin que el compuesto parcial baje del cielo.
     */
    private static final float BOTTOM_SHADE = 0.78F;

    /**
     * Opacidad que debe alcanzar la pila completa de cortes de una capa, sin importar cuantos sean.
     *
     * No llega a 1: una nube que tapa el cielo por completo deja de leerse como volumen.
     */
    private static final double STACK_OPACITY = 0.92D;

    /**
     * Cobertura maxima admitida. Con cobertura 1.0 no queda un solo punto del cielo por debajo del
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
     *                      disenada; mas alto agranda las formaciones, mas bajo despeja el cielo.
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
     * La cobertura desplaza el umbral en vez de escalar el resultado: asi subirla agranda las
     * formaciones existentes en lugar de volver todo el cielo uniformemente mas opaco.
     */
    public double densityAt(double cloudX, double cloudZ) {
        double raw = this.noise.fbm(cloudX / this.layer.noiseScale(), cloudZ / this.layer.noiseScale());
        double floor = 1.0D - this.effectiveCoverage();
        if (raw <= floor) {
            return 0.0D;
        }
        return Math.min(1.0D, (raw - floor) / Math.max(1.0E-6D, 1.0D - floor));
    }

    /** Peldanos de la escalera de alturas. Es el numero de cortes del nivel mas detallado. */
    private static final int LADDER_STEPS = 8;

    /**
     * Posicion vertical normalizada, en (0,1), del corte {@code index} de {@code slices}.
     *
     * <h2>Por que hay una escalera compartida y no una division propia por nivel</h2>
     *
     * Hasta la 0.2.3 cada nivel repartia sus cortes por su cuenta: {@code (index + 0,5) / cortes}.
     * El resultado era que <em>ninguna</em> altura coincidia entre niveles vecinos - ocho cortes en
     * 173, 175, ... 187 contra cuatro en 174, 178, 182, 186 - y eso es lo que producia las lineas
     * rectas que cruzaban el cielo.
     *
     * El mecanismo: dos regiones vecinas con distinto nivel de detalle comparten un borde recto de
     * 256 bloques. Mirando en angulo rasante, en la banda de pantalla que cruza esa costura se ven
     * los cortes de los dos lados entrelazados -doce planos distintos donde a cada lado hay ocho o
     * cuatro- y el alfa acumulado sube de 0,92 a 0,994. Un 8% mas de opacidad en una banda fina,
     * larga y perfectamente recta: poco contraste, pero el ojo detecta una recta de inmediato.
     *
     * Con la escalera, las alturas de un nivel grueso son un <em>subconjunto exacto</em> de las del
     * fino, asi que en la costura los planos coinciden en vez de entrelazarse.
     */
    public static double sliceT(int index, int slices) {
        if (slices <= 1) {
            return 0.5D;
        }
        int step = Math.max(1, LADDER_STEPS / slices);
        // El desplazamiento impar mantiene el anidamiento: {1,3,5,7} para cuatro cortes y {1,5}
        // para dos, los dos contenidos en {0..7}. Con desplazamiento cero los de dos cortes caerian
        // en posiciones pares que el nivel de cuatro no tiene.
        int rung = index * step + (step == 1 ? 0 : 1);
        rung = Math.min(rung, LADDER_STEPS - 1);
        return (rung * 2 + 1) / (double) (LADDER_STEPS * 2);
    }

    /**
     * Umbral de densidad en una posicion vertical normalizada.
     *
     * El medio acepta casi cualquier densidad; los extremos exigen densidad alta, y por eso solo el
     * nucleo de una formacion llega arriba y abajo.
     *
     * Depende de la altura y no del indice del corte: si dependiera del indice, dos cortes
     * coplanares de niveles distintos tendrian umbrales distintos y la costura volveria a verse
     * aunque las alturas coincidieran.
     */
    public double thresholdAt(double t) {
        double fromCenter = Math.abs(t - 0.5D) * 2.0D;
        return 0.06D + 0.62D * Math.pow(fromCenter, 1.6D);
    }

    /** Umbral del slice {@code index} de {@code slices}. */
    public double sliceThreshold(int index, int slices) {
        if (slices <= 1) {
            // Con un solo slice no hay perfil que construir: se dibuja el cuerpo de la nube.
            return 0.18D;
        }
        return this.thresholdAt(sliceT(index, slices));
    }

    /** Altura del slice, en bloques. */
    public double sliceHeight(int index, int slices) {
        return this.layer.baseHeight() + sliceT(index, slices) * this.layer.thickness();
    }

    /**
     * Opacidad que le toca a cada corte para que la pila entera llegue siempre a la misma.
     *
     * Hasta la 0.2.1 el alfa por corte era una constante -0,55 apilado, 0,85 solo- y la opacidad de
     * la pila salia de cuantos cortes hubiera. Eso tenia dos consecuencias, las dos visibles:
     *
     * <ul>
     *   <li>Con ocho cortes la pila llegaba a 0,998. El nucleo de una formacion era una pared
     *       opaca, que es lo contrario de lo que el sistema de cortes existe para producir.</li>
     *   <li>Cada cambio de nivel de detalle cambiaba la opacidad: 0,998 con ocho cortes, 0,959 con
     *       cuatro, 0,798 con dos. Veinte puntos de brillo de golpe al cruzar un umbral de
     *       distancia, que es buena parte del "popping" que se ve volando.</li>
     * </ul>
     *
     * Con esta formula el nivel de detalle cambia la estructura interna de la nube y no su
     * densidad aparente, que es lo que debe hacer un LOD.
     */
    public static float sliceAlpha(int slices) {
        int n = Math.max(1, slices);
        return (float) (1.0D - Math.pow(1.0D - STACK_OPACITY, 1.0D / n));
    }

    /** La opacidad a la que converge una pila completa. Para tests y diagnostico. */
    public static double stackOpacity() {
        return STACK_OPACITY;
    }

    /**
     * Opacidad de una celda en un slice, en [0,1]. Cero significa que no se emite geometria.
     *
     * La transicion suave en el borde es lo que evita el aspecto de bloques que el documento
     * descarta explicitamente (Seccion 12).
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
     * Dos terminos, los dos baratos: los slices bajos reciben menos luz que los altos, y las zonas
     * mas densas se oscurecen un poco mas. Es una aproximacion de la luz que se pierde atravesando
     * la nube, no un calculo fisico, y alcanza para que deje de leerse como una pared plana.
     */
    public float shade(double density, int sliceIndex, int slices) {
        // Igual que el umbral: en funcion de la altura y no del indice, para que dos cortes
        // coplanares de niveles distintos tengan el mismo color y la costura no se vea.
        float vertical = slices <= 1
                ? 0.88F
                : BOTTOM_SHADE + (1.0F - BOTTOM_SHADE) * (float) sliceT(sliceIndex, slices);
        float byDensity = 1.0F - 0.12F * (float) density;
        return vertical * byDensity;
    }

    /**
     * Brillo extra por dispersion hacia adelante: el borde luminoso de las nubes a contraluz
     * (Seccion 11.2).
     *
     * @param sunDotView producto punto entre la direccion al sol y la direccion de vista, -1..1
     * @return multiplicador, 1.0 cuando el sol no esta de frente
     */
    public static float forwardScatter(double sunDotView) {
        if (sunDotView <= 0.0D) {
            return 1.0F;
        }
        // Exponente alto: el efecto aparece solo cuando se mira bastante hacia el sol.
        return 1.0F + 0.35F * (float) Math.pow(sunDotView, 6.0D);
    }
}
