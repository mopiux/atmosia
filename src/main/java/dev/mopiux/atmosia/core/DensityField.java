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

    /**
     * Ancho de la transicion entre "no hay nube" y "nube opaca", medido en densidad.
     *
     * Estuvo en 0,22 hasta la 0.2.4, y ese valor era del mismo orden que el salto de umbral entre
     * dos cortes vecinos (0,21 en el peor caso). La consecuencia es que la silueta de cada corte
     * terminaba justo donde empezaba la del siguiente, sin solaparse: vista de canto, la pila se
     * leia como una escalera de terrazas, un escalon por corte.
     *
     * 0,38 hace que la silueta de un corte se extienda sobre casi tres saltos de umbral, asi que
     * las siluetas vecinas se superponen y el conjunto pasa de escalones a degradado.
     */
    private static final double EDGE_SOFTNESS = 0.38D;

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
        return 0.06D + THRESHOLD_RANGE * Math.pow(fromCenter, THRESHOLD_EXPONENT);
    }

    /**
     * Cuanto mas exigente es el corte mas extremo respecto del central.
     *
     * Estuvo en 0,62 con exponente 1,6, y ese rango era demasiado ancho para las densidades que el
     * campo produce de verdad. Medido sobre 640.000 muestras de la capa baja: el 97% de las celdas
     * con nube quedan por debajo de 0,6 y la densidad media es 0,19. Con el umbral extremo en
     * 0,561, los cortes de arriba y de abajo alcanzaban al 1,5% de las celdas -practicamente no se
     * dibujaban- y del 1,5% se saltaba al 15% del corte siguiente. Ese salto es el escalon.
     *
     * 0,30 con exponente 1,35 reparte los ocho cortes sobre el rango donde el campo realmente
     * vive: cubren el 21%, 38%, 56% y 72% de las celdas con nube, y de ahi hacia abajo en espejo.
     * El perfil vertical se conserva -el corte extremo sigue exigiendo cuatro veces la densidad
     * del central- y el salto maximo entre cortes vecinos baja de 0,21 a 0,09 con ocho cortes y a
     * 0,17 con cuatro, en los dos casos bien por debajo del ancho de la transicion de borde.
     */
    private static final double THRESHOLD_RANGE = 0.30D;
    private static final double THRESHOLD_EXPONENT = 1.35D;

    /** El salto de umbral mas grande entre dos cortes vecinos. Para tests y diagnostico. */
    public double maxThresholdGap(int slices) {
        if (slices <= 1) {
            return 0.0D;
        }
        double peor = 0.0D;
        for (int i = 0; i + 1 < slices; i++) {
            peor = Math.max(peor, Math.abs(this.sliceThreshold(i + 1, slices)
                    - this.sliceThreshold(i, slices)));
        }
        return peor;
    }

    /** El ancho de la transicion de borde. Para tests. */
    public static double edgeSoftness() {
        return EDGE_SOFTNESS;
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
     * Cuanto pesa cada corte dentro de la pila, segun su altura normalizada.
     *
     * Hasta la 0.2.4 todos los cortes tenian el mismo alfa, asi que la pila empezaba y terminaba
     * de golpe: el corte mas bajo aportaba tanto como el central y el borde inferior de la capa
     * era un canto duro. Vista de canto, esa pila se lee como un juego de laminas apiladas.
     *
     * Con el peso, los cortes de los extremos aportan poco menos de seis decimos de lo que aporta
     * el central, y la capa se desvanece hacia arriba y hacia abajo en vez de cortarse.
     */
    private static final double EDGE_TAPER = 0.55D;

    private static double sliceWeight(int index, int slices) {
        if (slices <= 1) {
            return 1.0D;
        }
        double fromCenter = Math.abs(sliceT(index, slices) - 0.5D) * 2.0D;
        return 1.0D - EDGE_TAPER * fromCenter * fromCenter;
    }

    /**
     * Alfa de cada corte de la pila, con el peso vertical ya aplicado y normalizado.
     *
     * La normalizacion es la que mantiene la invariante de la 0.2.2: sea cual sea la cantidad de
     * cortes y sea cual sea el reparto de pesos, la pila completa converge siempre a
     * {@link #stackOpacity()}. Asi el nivel de detalle cambia la estructura interna de la nube y
     * no su densidad aparente.
     *
     * Se resuelve por biseccion sobre el factor comun: hay que encontrar el {@code s} tal que
     * {@code producto(1 - s * peso_i) = 1 - opacidad}. La suma de logaritmos es monotona
     * decreciente en {@code s}, asi que la biseccion converge sin sorpresas. Se llama una vez por
     * malla construida, no por cuadrilatero.
     */
    public static float[] sliceAlphas(int slices) {
        int n = Math.max(1, slices);
        float[] out = new float[n];
        if (n == 1) {
            out[0] = (float) STACK_OPACITY;
            return out;
        }

        double[] peso = new double[n];
        double mayor = 0.0D;
        for (int i = 0; i < n; i++) {
            peso[i] = sliceWeight(i, n);
            mayor = Math.max(mayor, peso[i]);
        }

        double objetivo = Math.log(1.0D - STACK_OPACITY);
        double bajo = 0.0D;
        double alto = 1.0D / mayor;
        for (int paso = 0; paso < 64; paso++) {
            double s = 0.5D * (bajo + alto);
            double suma = 0.0D;
            for (int i = 0; i < n; i++) {
                suma += Math.log(1.0D - s * peso[i]);
            }
            if (suma > objetivo) {
                bajo = s;
            } else {
                alto = s;
            }
        }

        double s = 0.5D * (bajo + alto);
        for (int i = 0; i < n; i++) {
            out[i] = (float) (s * peso[i]);
        }
        return out;
    }

    /** El alfa de un corte suelto. Comodidad para tests: construye la escalera entera. */
    public static float sliceAlpha(int index, int slices) {
        float[] todos = sliceAlphas(slices);
        return todos[Math.max(0, Math.min(todos.length - 1, index))];
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
        if (over >= 1.0D) {
            return 1.0F;
        }
        // Suavizado hermite en vez de rampa recta. Una rampa recta es continua pero su derivada no:
        // hay un quiebre donde la nube empieza y otro donde satura, y un quiebre en el alfa es
        // exactamente lo que el ojo lee como una linea cuando se mira la capa de canto. Con
        // t^2(3-2t) la derivada se anula en los dos extremos y la silueta entra y sale sin canto.
        return (float) (over * over * (3.0D - 2.0D * over));
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
