package dev.mopiux.atmosia.core;

/**
 * Convierte el campo de densidad en una lista de bultos (sprites), para la tecnica SPRITES.
 *
 * <h2>Por que sprites y no geometria en grilla</h2>
 *
 * La tecnica de planos apilados parte el cielo en regiones de 256 bloques y dibuja cada una por
 * separado. La mezcla con transparencia depende del orden en que se dibujan las cosas, y al cruzar
 * el limite entre dos regiones ese orden cambia de golpe: aparece una recta de 256 bloques. Se
 * corrigieron cinco causas distintas de ese defecto y siempre aparecio una sexta.
 *
 * Con sprites el error de orden sigue existiendo -es inherente a la transparencia- pero cambia de
 * forma: en vez de concentrarse en una recta larga, se reparte entre miles de bultos sueltos como
 * ruido desordenado. Y esa es toda la diferencia: el ojo detecta una recta de un nivel de gris,
 * pero no detecta ruido del mismo nivel.
 *
 * <h2>Determinismo</h2>
 *
 * Los bultos salen del mismo ruido que todo lo demas, asi que la misma seed y la misma region dan
 * siempre los mismos bultos. El desorden de posicion y tamano tambien sale de un hash de la celda,
 * no de un generador aleatorio con estado: una region se puede tirar y recalcular y queda igual.
 */
public final class PuffField {

    /** Separacion de la grilla de siembra, en bloques. Mas chico, mas bultos y mas suave. */
    public static final int SEED_SPACING = 24;

    /** Densidad minima para que nazca un bulto. Debajo de esto la nube es demasiado tenue. */
    private static final double MIN_DENSITY = 0.10D;

    /** Radio de un bulto, en bloques: base mas lo que aporta la densidad. */
    private static final double RADIUS_BASE = 22.0D;
    private static final double RADIUS_BY_DENSITY = 26.0D;

    /** Cuanto se desordena la posicion, como fraccion de la separacion. */
    private static final double JITTER = 0.42D;

    /** Opacidad maxima de un bulto suelto. Baja a proposito: el volumen sale de superponer. */
    private static final double MAX_ALPHA = 0.50D;

    /** Cuantos bultos se apilan en vertical, como maximo, en el nucleo de una formacion. */
    public static final int MAX_STACK = 4;

    private PuffField() {
    }

    /**
     * Un bulto.
     *
     * @param x      posicion en espacio de nube
     * @param y      altura en bloques
     * @param z      posicion en espacio de nube
     * @param radius radio en bloques
     * @param alpha  opacidad propia, 0..1
     * @param shade  multiplicador de color, ya con la altura aplicada
     */
    public record Puff(float x, float y, float z, float radius, float alpha, float shade) {
    }

    /** Cuantos bultos le tocan a una columna segun su densidad. */
    public static int stackFor(double density) {
        if (density < MIN_DENSITY) {
            return 0;
        }
        return Math.max(1, Math.min(MAX_STACK, (int) (1 + density * MAX_STACK)));
    }

    /**
     * Desorden determinista en [-0.5, 0.5] a partir de una celda y un canal.
     *
     * Hash entero, igual que el ruido: no se degrada lejos del origen y no necesita estado.
     */
    public static double jitter(long cellX, long cellZ, int channel, long seed) {
        long h = seed;
        h ^= cellX * 0x9E3779B97F4A7C15L;
        h ^= cellZ * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) channel * 0x165667B19E3779F9L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53D - 0.5D;
    }

    /** Perfil vertical de una capa: cero en los bordes, uno en el medio. */
    public static double verticalProfile(double t) {
        double s = Math.sin(Math.PI * Math.max(0.0D, Math.min(1.0D, t)));
        return s * s;
    }

    /**
     * Siembra los bultos de una region y los entrega al consumidor.
     *
     * @param field      campo de densidad de la capa, con la cobertura del jugador ya aplicada
     * @param layer      la capa
     * @param originX    esquina menor de la region, en espacio de nube
     * @param originZ    idem
     * @param size       lado de la region en bloques
     * @param seed       seed del mundo, para el desorden
     * @param out        recibe cada bulto
     */
    public static void seed(DensityField field, CloudLayerDef layer, double originX, double originZ,
                            int size, long seed, java.util.function.Consumer<Puff> out) {
        int steps = Math.max(1, size / SEED_SPACING);
        for (int cz = 0; cz < steps; cz++) {
            for (int cx = 0; cx < steps; cx++) {
                long cellX = (long) Math.floor((originX + cx * (double) SEED_SPACING) / SEED_SPACING);
                long cellZ = (long) Math.floor((originZ + cz * (double) SEED_SPACING) / SEED_SPACING);

                double x = originX + (cx + 0.5D) * SEED_SPACING
                        + jitter(cellX, cellZ, 0, seed) * SEED_SPACING * JITTER;
                double z = originZ + (cz + 0.5D) * SEED_SPACING
                        + jitter(cellX, cellZ, 1, seed) * SEED_SPACING * JITTER;

                double density = field.densityAt(x, z);
                int stack = stackFor(density);
                if (stack == 0) {
                    continue;
                }

                for (int k = 0; k < stack; k++) {
                    double t = (k + 0.5D) / stack
                            + jitter(cellX, cellZ, 2 + k, seed) * 0.5D / stack;
                    t = Math.max(0.05D, Math.min(0.95D, t));
                    double profile = verticalProfile(t);
                    double alpha = Math.min(MAX_ALPHA, density * profile * 1.15D);
                    if (alpha <= 0.01D) {
                        continue;
                    }
                    double radius = (RADIUS_BASE + RADIUS_BY_DENSITY * density)
                            * (0.82D + 0.36D * (jitter(cellX, cellZ, 6 + k, seed) + 0.5D));
                    // Cada bulto de la pila se corre tambien en horizontal. Sin esto, una pila es
                    // una columna perfectamente vertical de bultos en la MISMA posicion, y varias
                    // columnas asi vuelven a alinearse con la grilla de siembra. Ademas una nube de
                    // verdad no tiene sus bultos uno exactamente encima del otro.
                    double despX = jitter(cellX, cellZ, 12 + k, seed) * SEED_SPACING * 0.55D;
                    double despZ = jitter(cellX, cellZ, 18 + k, seed) * SEED_SPACING * 0.55D;
                    // Mismo gradiente vertical que las otras tecnicas: la base mas oscura.
                    double shade = (0.78D + 0.22D * t) * (1.0D - 0.12D * density);
                    out.accept(new Puff(
                            (float) (x + despX),
                            (float) (layer.baseHeight() + t * layer.thickness()),
                            (float) (z + despZ),
                            (float) radius,
                            (float) alpha,
                            (float) shade));
                }
            }
        }
    }
}
