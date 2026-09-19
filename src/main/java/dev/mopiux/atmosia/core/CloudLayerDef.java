package dev.mopiux.atmosia.core;

/**
 * Parametros de una capa de nubes (Seccion 2 del documento de diseno).
 *
 * Las tres capas no son tres planos independientes: comparten el mismo campo de ruido con escalas
 * y cortes distintos, asi que donde coinciden la densidad se acumula y se lee como una masa mas
 * profunda en vez de como tres laminas superpuestas.
 *
 * @param name        identificador para logs y metricas
 * @param baseHeight  altura del piso de la capa, en bloques
 * @param thickness   espesor vertical, en bloques
 * @param red         componente de color, 0..1
 * @param green       componente de color, 0..1
 * @param blue        componente de color, 0..1
 * @param speedX      velocidad de deriva en X, en bloques por segundo
 * @param speedZ      velocidad de deriva en Z, en bloques por segundo
 * @param coverage    cuanto cielo cubre, 0..1. Mas alto, mas nube.
 * @param noiseScale  bloques por unidad de ruido. Mas alto, formaciones mas grandes.
 */
public record CloudLayerDef(
        String name,
        double baseHeight,
        double thickness,
        float red,
        float green,
        float blue,
        double speedX,
        double speedZ,
        double coverage,
        double noiseScale) {

    /**
     * Capa alta: dispersa y sutil, gris levemente azulado, la mas rapida.
     * Las velocidades distintas por capa son lo que produce la sensacion de profundidad cuando se
     * mira hacia arriba un rato (Seccion 10).
     */
    public static final CloudLayerDef HIGH =
            new CloudLayerDef("high", 216.0D, 14.0D, 0.92F, 0.94F, 1.00F, 1.6D, 0.4D, 0.26D, 620.0D);

    /** Capa media: densidad intermedia, gris neutro. */
    public static final CloudLayerDef MID =
            new CloudLayerDef("mid", 192.0D, 20.0D, 0.96F, 0.96F, 0.97F, 1.0D, 0.25D, 0.36D, 420.0D);

    /** Capa baja: mayor cobertura, gris apenas mas oscuro, la mas lenta. */
    public static final CloudLayerDef LOW =
            new CloudLayerDef("low", 172.0D, 16.0D, 0.88F, 0.89F, 0.92F, 0.6D, 0.15D, 0.42D, 300.0D);

    public static final CloudLayerDef[] DEFAULTS = { LOW, MID, HIGH };

    /** Techo de la capa. */
    public double topHeight() {
        return this.baseHeight + this.thickness;
    }

    /** Centro vertical, que es la altura contra la que se calcula el fade vertical. */
    public double centerHeight() {
        return this.baseHeight + this.thickness * 0.5D;
    }

    /**
     * Desplazamiento acumulado de la capa a un tiempo dado.
     *
     * El movimiento se resuelve con este offset y nunca regenerando geometria: las regiones viven
     * en espacio de nube, no en espacio de mundo, asi que su densidad no cambia jamas y lo unico
     * que se mueve es donde se dibujan (Seccion 5.1).
     */
    public double windOffsetX(double seconds) {
        return this.speedX * seconds;
    }

    public double windOffsetZ(double seconds) {
        return this.speedZ * seconds;
    }
}
