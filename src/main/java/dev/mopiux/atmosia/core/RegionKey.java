package dev.mopiux.atmosia.core;

/**
 * Identifica una region de nubes en espacio de nube (Seccion 5.1).
 *
 * En espacio de nube, no de mundo: cada capa tiene su propia deriva, asi que sus regiones se
 * indexan sobre coordenadas ya descontado el viento. La consecuencia es la que busca el documento:
 * la densidad de una region no cambia nunca, y el movimiento es una traslacion al dibujar.
 *
 * @param layer indice de capa
 * @param x     coordenada de region
 * @param z     coordenada de region
 */
public record RegionKey(int layer, int x, int z) {

    /** Lado de una region, en bloques. Potencia de dos para que todos los LOD la dividan exacto. */
    public static final int REGION_SIZE = 256;

    /** Region que contiene un punto ya expresado en espacio de nube. */
    public static RegionKey of(int layer, double cloudX, double cloudZ) {
        return new RegionKey(layer, floorDiv(cloudX), floorDiv(cloudZ));
    }

    /** Esquina menor de la region, en espacio de nube. */
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
