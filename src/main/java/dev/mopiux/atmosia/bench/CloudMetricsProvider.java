package dev.mopiux.atmosia.bench;

import javax.annotation.Nullable;

/**
 * Métricas que solo puede aportar el renderer de nubes en uso.
 *
 * El harness mide tiempos por su cuenta, sin saber qué está dibujando. Todo lo que depende de la
 * implementación —regiones en caché, vértices, draw calls, memoria— entra por acá.
 *
 * Cuando no hay proveedor registrado (que es el caso al medir las nubes vanilla, la línea base),
 * esas columnas del CSV quedan vacías. Vacío significa "no medido", nunca cero: la diferencia
 * importa cuando después se comparen fases.
 */
public interface CloudMetricsProvider {

    /** Nombre del renderer para la columna correspondiente del CSV. */
    String rendererName();

    /** Regiones vivas en caché, o -1 si no aplica. */
    int activeRegions();

    /** Regiones esperando generación en la cola de prioridad, o -1 si no aplica. */
    int queuedRegions();

    /** Vértices enviados por el sistema de nubes en el último frame, o -1. */
    long verticesLastFrame();

    /** Draw calls emitidos por el sistema de nubes en el último frame, o -1. */
    int drawCallsLastFrame();

    /** Bytes ocupados por la caché en memoria principal, o -1. */
    long cacheBytes();

    /** Bytes ocupados en GPU por buffers y texturas del sistema, o -1. */
    long gpuBytes();

    /** Milisegundos que tardó la última generación de región completada, o -1. */
    double lastRegionGenerationMillis();

    // ---------------------------------------------------------------------------------------

    final class Registry {
        @Nullable
        private static volatile CloudMetricsProvider current;

        private Registry() {
        }

        /**
         * Registra el proveedor activo. Lo llamará el renderer de Atmosia cuando exista; mientras
         * no haya ninguno, el harness mide igual y reporta la línea base de vanilla.
         */
        public static void set(@Nullable CloudMetricsProvider provider) {
            current = provider;
        }

        @Nullable
        public static CloudMetricsProvider get() {
            return current;
        }
    }
}
