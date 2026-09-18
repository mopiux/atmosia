package dev.mopiux.atmosia.core;

/**
 * Campo de ruido determinista para la densidad de nubes (Sección 4 del documento de diseño).
 *
 * Es ruido de valor con interpolación suave y varias octavas. No es el ruido más bonito que
 * existe, y esa es la idea: el documento pide priorizar rendimiento sobre espectacularidad al
 * elegir la función. Se evalúa muchísimas veces por región, así que cada operación cuenta.
 *
 * Determinismo: la misma seed y las mismas coordenadas dan siempre el mismo valor, sin estado ni
 * tablas de permutación que inicializar. Volver a mirar una zona del cielo la muestra igual.
 *
 * Precisión: el hash trabaja sobre coordenadas enteras de celda en long, no sobre floats de
 * coordenadas absolutas, así que no se degrada lejos del origen. Es la otra mitad de la estrategia
 * de floating origin: las regiones aportan coordenadas locales y el ruido nunca ve un número
 * grande en punto flotante.
 */
public final class NoiseField {

    private static final int OCTAVES = 4;
    private static final double LACUNARITY = 2.0D;
    private static final double GAIN = 0.5D;

    private final long seed;

    public NoiseField(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return this.seed;
    }

    /**
     * Ruido fractal en [0,1].
     *
     * @param x coordenada ya dividida por la escala de la capa
     * @param z idem
     */
    public double fbm(double x, double z) {
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;
        double normalization = 0.0D;

        for (int octave = 0; octave < OCTAVES; octave++) {
            total += this.valueNoise(x * frequency, z * frequency, octave) * amplitude;
            normalization += amplitude;
            amplitude *= GAIN;
            frequency *= LACUNARITY;
        }
        return total / normalization;
    }

    /** Ruido de valor de una octava, en [0,1]. */
    public double valueNoise(double x, double z, int octave) {
        long cellX = floor(x);
        long cellZ = floor(z);
        double fx = x - cellX;
        double fz = z - cellZ;

        // Suavizado hermite: la derivada se anula en los bordes de celda, así no se ven las
        // costuras de la grilla como líneas rectas en el cielo.
        double sx = fx * fx * (3.0D - 2.0D * fx);
        double sz = fz * fz * (3.0D - 2.0D * fz);

        double c00 = this.hashToUnit(cellX, cellZ, octave);
        double c10 = this.hashToUnit(cellX + 1, cellZ, octave);
        double c01 = this.hashToUnit(cellX, cellZ + 1, octave);
        double c11 = this.hashToUnit(cellX + 1, cellZ + 1, octave);

        double top = c00 + (c10 - c00) * sx;
        double bottom = c01 + (c11 - c01) * sx;
        return top + (bottom - top) * sz;
    }

    /** Hash entero a [0,1). Mezcla estilo splitmix64: barata y sin patrones visibles. */
    private double hashToUnit(long x, long z, int octave) {
        long h = this.seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) octave * 0x165667B19E3779F9L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        // 53 bits en la mantisa de un double: usar más no aporta nada.
        return (h >>> 11) * 0x1.0p-53D;
    }

    private static long floor(double value) {
        long truncated = (long) value;
        return value < truncated ? truncated - 1L : truncated;
    }
}
