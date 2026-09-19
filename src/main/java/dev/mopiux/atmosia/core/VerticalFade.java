package dev.mopiux.atmosia.core;

/**
 * Culling vertical con transicion gradual (Seccion 6.3).
 *
 * El documento es explicito en que un corte binario produce popping y no se acepta: la capa que
 * sale del rango razonable de vision baja su opacidad progresivamente, y el descarte total de
 * geometria solo ocurre cuando el fade ya llego cerca de cero, de forma que el propio fade tape el
 * corte real.
 */
public final class VerticalFade {

    /** Por debajo de esta opacidad no vale la pena dibujar: es donde se descarta de verdad. */
    public static final float CULL_THRESHOLD = 0.02F;

    /** Distancia vertical a partir de la cual empieza a desvanecerse, en bloques. */
    private final double fadeStart;

    /** Distancia vertical a la que ya es invisible. */
    private final double fadeEnd;

    public VerticalFade(double fadeStart, double fadeEnd) {
        if (fadeEnd <= fadeStart) {
            throw new IllegalArgumentException("fadeEnd debe ser mayor que fadeStart");
        }
        this.fadeStart = fadeStart;
        this.fadeEnd = fadeEnd;
    }

    public static VerticalFade defaults() {
        return new VerticalFade(180.0D, 420.0D);
    }

    /**
     * Opacidad de una capa vista desde una altura de camara.
     *
     * Estar dentro de la capa nunca atenua: es el caso en que mas se la ve. La atenuacion crece con
     * la distancia vertical a la capa, mires desde arriba o desde abajo.
     *
     * @param cameraY   altura de la camara
     * @param layer     capa evaluada
     * @param pitchDeg  inclinacion de la camara en grados, negativa mirando hacia arriba
     */
    public float opacity(double cameraY, CloudLayerDef layer, double pitchDeg) {
        double verticalDistance;
        if (cameraY < layer.baseHeight()) {
            verticalDistance = layer.baseHeight() - cameraY;
        } else if (cameraY > layer.topHeight()) {
            verticalDistance = cameraY - layer.topHeight();
        } else {
            return 1.0F;
        }

        double effective = verticalDistance;
        // Mirar hacia la capa la mantiene visible mas lejos: la direccion de la vista es parte del
        // culling, no solo la altura absoluta.
        boolean lookingAtLayer = (cameraY < layer.baseHeight() && pitchDeg < 0.0D)
                || (cameraY > layer.topHeight() && pitchDeg > 0.0D);
        if (lookingAtLayer) {
            double aim = Math.min(1.0D, Math.abs(pitchDeg) / 60.0D);
            effective *= 1.0D - 0.5D * aim;
        }

        if (effective <= this.fadeStart) {
            return 1.0F;
        }
        if (effective >= this.fadeEnd) {
            return 0.0F;
        }
        float t = (float) ((effective - this.fadeStart) / (this.fadeEnd - this.fadeStart));
        // Suavizado para que el arranque del fade tampoco se note como un quiebre.
        return 1.0F - (t * t * (3.0F - 2.0F * t));
    }

    /** Si con esta opacidad conviene saltarse el dibujo por completo. */
    public static boolean isCulled(float opacity) {
        return opacity < CULL_THRESHOLD;
    }
}
