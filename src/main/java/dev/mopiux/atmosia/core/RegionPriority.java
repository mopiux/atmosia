package dev.mopiux.atmosia.core;

/**
 * Prioridad de generacion de una region (Seccion 8.1).
 *
 * Reproduce la tabla del documento: visible y cerca primero, fuera de camara ultimo. El numero es
 * menor cuanto mas urgente, para poder ordenarlo de forma natural.
 */
public final class RegionPriority {

    public static final int VISIBLE_NEAR = 0;
    public static final int VISIBLE_MID = 1;
    public static final int VISIBLE_FAR = 2;
    public static final int BEHIND_PLAYER = 3;
    public static final int OFF_CAMERA = 4;

    private RegionPriority() {
    }

    /**
     * Clasifica una region.
     *
     * @param distance  distancia de la camara al centro de la region
     * @param selector  escala de LOD vigente, que define que es cerca y que es lejos
     * @param inFrustum si el frustum culling la considera visible
     * @param dotFacing producto punto entre la direccion de vista y la direccion a la region
     */
    public static int classify(double distance, LodSelector selector, boolean inFrustum, double dotFacing) {
        if (!inFrustum) {
            return dotFacing < 0.0D ? BEHIND_PLAYER : OFF_CAMERA;
        }
        LodLevel level = selector.levelFor(distance);
        if (level == null) {
            return OFF_CAMERA;
        }
        return switch (level) {
            case HIGH -> VISIBLE_NEAR;
            case MEDIUM -> VISIBLE_MID;
            default -> VISIBLE_FAR;
        };
    }

    /**
     * Clave de orden dentro de la cola: primero la clase, y dentro de cada clase la mas cercana.
     * Mezclar ambas cosas en un solo long evita comparar dos veces al ordenar.
     */
    public static long sortKey(int priorityClass, double distance) {
        long distanceBits = (long) Math.min(Integer.MAX_VALUE, Math.max(0.0D, distance));
        return ((long) priorityClass << 32) | distanceBits;
    }

    /** Si vale la pena generar una region de esta clase. Fuera de camara no se genera. */
    public static boolean shouldGenerate(int priorityClass) {
        return priorityClass <= BEHIND_PLAYER;
    }
}
