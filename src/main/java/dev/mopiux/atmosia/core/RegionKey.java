package dev.mopiux.atmosia.core;

/**
 * Identifica una región de nubes en espacio de nube (Sección 5.1).
 *
 * En espacio de nube, no de mundo: cada capa tiene su propia deriva, así que sus regiones se
 * indexan sobre coordenadas ya descontado el viento. La consecuencia es la que busca el documento:
 * la densidad de una región no cambia nunca, y el movimiento es una traslación al dibujar.
 *
 * @param layer índice de capa
 * @param x     coordenada de región
 * @param z     coordenada de región
 */
public record RegionKey(int layer, int x, int z) {

    /** Lado de una región, en bloques. Potencia de dos para que todos los LOD la dividan exacto. */
    public static final int REGION_SIZE = 256;

    /** Región que contiene un punto ya expresado en espacio de nube. */
    public static RegionKey of(int layer, double cloudX, double cloudZ) {
        return new RegionKey(layer, floorDiv(cloudX), floorDiv(cloudZ));
    }

    /** Esquina menor de la región, en espacio de nube. */
    public double originX() {
        return (double) this.x * REGION_SIZE;
    }

    public double originZ() {
        return (double) this.z * REGION_SIZE;
    }

    public double centerX() {
        return this.originX() + REGION_SIZE * 0.5D;
    }

    public double centerZ() {
        return this.originZ() + REGION_SIZE * 0.5D;
    }

    private static int floorDiv(double value) {
        return (int) Math.floor(value / REGION_SIZE);
    }
}
