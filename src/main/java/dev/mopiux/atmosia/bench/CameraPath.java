package dev.mopiux.atmosia.bench;

/**
 * Trayectoria determinista de camara.
 *
 * La posicion y los angulos son funcion del tiempo transcurrido en segundos, nunca del numero de
 * frame: asi la misma corrida recorre exactamente el mismo camino en una maquina que rinde 30 FPS
 * y en una que rinde 300, que es lo que hace comparables las mediciones entre fases.
 */
public final class CameraPath {

    /**
     * Altura de la capa de nubes vanilla en el Overworld.
     * VERIFICAR: confirmar el valor real en 1.20.1 (efectos de dimension del Overworld) y, mas
     * adelante, leerlo de la configuracion de Atmosia en vez de dejarlo fijo aca.
     */
    public static final double VANILLA_CLOUD_HEIGHT = 192.0D;

    /** Punto de partida fijo. Cualquier valor sirve mientras no cambie entre corridas. */
    public static final double ORIGIN_X = 0.5D;
    public static final double ORIGIN_Z = 0.5D;

    private static final double BELOW_OFFSET = -40.0D;
    private static final double INSIDE_OFFSET = 2.0D;
    private static final double ABOVE_OFFSET = 60.0D;

    /** Grados por segundo del giro. 90 equivale a una vuelta completa cada cuatro segundos. */
    private static final double ORBIT_DEGREES_PER_SECOND = 90.0D;

    /** Bloques por segundo del tramo de vuelo. Aproxima el ritmo de unos elitros. */
    private static final double FLIGHT_BLOCKS_PER_SECOND = 40.0D;

    /** Amplitud y periodo del barrido vertical. */
    private static final double SWEEP_AMPLITUDE = 70.0D;
    private static final double SWEEP_PERIOD_SECONDS = 8.0D;

    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    private final BenchmarkScenario scenario;
    private final double cloudHeight;

    public CameraPath(BenchmarkScenario scenario, double cloudHeight) {
        this.scenario = scenario;
        this.cloudHeight = cloudHeight;
    }

    /** Pose de la camara a los {@code seconds} segundos de iniciada la trayectoria. */
    public Pose poseAt(double seconds) {
        double baseY = this.baseAltitude();
        double x = ORIGIN_X;
        double z = ORIGIN_Z;
        double y = baseY;
        float yaw = 0.0F;
        float pitch = 0.0F;

        switch (this.scenario.movement()) {
            case STATIC -> {
                // Nada que mover: la gracia del escenario es justamente que no cambie nada.
            }
            case ORBIT -> yaw = (float) wrapDegrees(seconds * ORBIT_DEGREES_PER_SECOND);
            case FLIGHT -> {
                // Vuelo recto mirando hacia adelante: estresa generacion y cache, no el culling.
                x = ORIGIN_X + seconds * FLIGHT_BLOCKS_PER_SECOND;
                yaw = 90.0F;
            }
            case ALTITUDE -> {
                double phase = (seconds % SWEEP_PERIOD_SECONDS) / SWEEP_PERIOD_SECONDS;
                y = baseY + Math.sin(phase * 2.0D * Math.PI) * SWEEP_AMPLITUDE;
                // Mirar levemente hacia arriba hace visible el fade vertical mientras se sube.
                pitch = -20.0F;
            }
        }

        return new Pose(x, y, z, yaw, pitch);
    }

    /**
     * Altura de referencia del escenario. En SWEEP se parte de la altura de la capa para que el
     * barrido la cruce en ambos sentidos.
     */
    private double baseAltitude() {
        return switch (this.scenario.altitude()) {
            case BELOW -> this.cloudHeight + BELOW_OFFSET;
            case INSIDE -> this.cloudHeight + INSIDE_OFFSET;
            case ABOVE -> this.cloudHeight + ABOVE_OFFSET;
            case SWEEP -> this.cloudHeight;
        };
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }
}
