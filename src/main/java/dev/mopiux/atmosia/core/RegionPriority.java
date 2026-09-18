package dev.mopiux.atmosia.core;

/**
 * Prioridad de generación de una región (Sección 8.1).
 *
 * Reproduce la tabla del documento: visible y cerca primero, fuera de cámara último. El número es
 * menor cuanto más urgente, para poder ordenarlo de forma natural.
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
     * Clasifica una región.
     *
     * @param distance  distancia de la cámara al centro de la región
     * @param selector  escala de LOD vigente, que define qué es cerca y qué es lejos
     * @param inFrustum si el frustum culling la considera visible
     * @param dotFacing producto punto entre la dirección de vista y la dirección a la región
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
     * Clave de orden dentro de la cola: primero la clase, y dentro de cada clase la más cercana.
     * Mezclar ambas cosas en un solo long evita comparar dos veces al ordenar.
     */
    public static long sortKey(int priorityClass, double distance) {
        long distanceBits = (long) Math.min(Integer.MAX_VALUE, Math.max(0.0D, distance));
        return ((long) priorityClass << 32) | distanceBits;
    }

    /** Si vale la pena generar una región de esta clase. Fuera de cámara no se genera. */
    public static boolean shouldGenerate(int priorityClass) {
        return priorityClass <= BEHIND_PLAYER;
    }
}
