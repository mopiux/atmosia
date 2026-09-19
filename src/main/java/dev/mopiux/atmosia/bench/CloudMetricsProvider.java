package dev.mopiux.atmosia.bench;

import javax.annotation.Nullable;

/**
 * Metricas que solo puede aportar el renderer de nubes en uso.
 *
 * El harness mide tiempos por su cuenta, sin saber que esta dibujando. Todo lo que depende de la
 * implementacion -regiones en cache, vertices, draw calls, memoria- entra por aca.
 *
 * Cuando no hay proveedor registrado (que es el caso al medir las nubes vanilla, la linea base),
 * esas columnas del CSV quedan vacias. Vacio significa "no medido", nunca cero: la diferencia
 * importa cuando despues se comparen fases.
 */
public interface CloudMetricsProvider {

    /** Nombre del renderer para la columna correspondiente del CSV. */
    String rendererName();

    /** Regiones vivas en cache, o -1 si no aplica. */
    int activeRegions();

    /** Regiones esperando generacion en la cola de prioridad, o -1 si no aplica. */
    int queuedRegions();

    /** Vertices enviados por el sistema de nubes en el ultimo frame, o -1. */
    long verticesLastFrame();

    /** Draw calls emitidos por el sistema de nubes en el ultimo frame, o -1. */
    int drawCallsLastFrame();

    /** Bytes ocupados por la cache en memoria principal, o -1. */
    long cacheBytes();

    /** Bytes ocupados en GPU por buffers y texturas del sistema, o -1. */
    long gpuBytes();

    /** Milisegundos que tardo la ultima generacion de region completada, o -1. */
    double lastRegionGenerationMillis();

    // ---------------------------------------------------------------------------------------

    final class Registry {
        @Nullable
        private static volatile CloudMetricsProvider current;

        private Registry() {
        }

        /**
         * Registra el proveedor activo. Lo llamara el renderer de Atmosia cuando exista; mientras
         * no haya ninguno, el harness mide igual y reporta la linea base de vanilla.
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
