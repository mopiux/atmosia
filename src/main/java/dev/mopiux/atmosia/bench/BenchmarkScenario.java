package dev.mopiux.atmosia.bench;

/**
 * Los nueve escenarios de prueba de la Seccion 16 del documento de diseno.
 *
 * Cada escenario fija una altura relativa a la capa de nubes y un movimiento de camara. La
 * combinacion de ambos es lo que hace la medicion repetible: misma trayectoria, misma duracion,
 * mismo punto de partida en cada corrida.
 */
public enum BenchmarkScenario {

    /** Cielo despejado: referencia de costo cuando no hay nubes que dibujar. */
    CLEAR_SKY("clear_sky", Altitude.BELOW, Movement.STATIC, Coverage.CLEAR),

    /** Maxima cobertura: el peor caso de relleno. */
    FULL_COVERAGE("full_coverage", Altitude.BELOW, Movement.STATIC, Coverage.MAX),

    /** Camara quieta: aisla el costo de estado estable, sin generacion ni streaming. */
    CAMERA_STILL("camera_still", Altitude.BELOW, Movement.STATIC, Coverage.DEFAULT),

    /** Giro rapido: estresa el frustum culling y el orden de dibujo. */
    CAMERA_SPIN("camera_spin", Altitude.BELOW, Movement.ORBIT, Coverage.DEFAULT),

    /** Vuelo rapido: estresa la generacion, la cache y la cola de prioridad. */
    FAST_TRAVEL("fast_travel", Altitude.BELOW, Movement.FLIGHT, Coverage.DEFAULT),

    /** Cambio de altura: estresa el culling vertical y el fade. */
    ALTITUDE_SWEEP("altitude_sweep", Altitude.SWEEP, Movement.ALTITUDE, Coverage.DEFAULT),

    /** Debajo de las nubes: el caso normal de juego. */
    BELOW_CLOUDS("below_clouds", Altitude.BELOW, Movement.STATIC, Coverage.DEFAULT),

    /** Dentro de las nubes: el caso que mas artefactos de transparencia produce. */
    INSIDE_CLOUDS("inside_clouds", Altitude.INSIDE, Movement.STATIC, Coverage.DEFAULT),

    /** Encima de las nubes: maxima cantidad de geometria visible a la vez. */
    ABOVE_CLOUDS("above_clouds", Altitude.ABOVE, Movement.STATIC, Coverage.DEFAULT);

    public enum Altitude { BELOW, INSIDE, ABOVE, SWEEP }

    public enum Movement { STATIC, ORBIT, FLIGHT, ALTITUDE }

    /**
     * Cobertura de nubes deseada. CLEAR y MAX solo son aplicables cuando el renderer permite
     * forzarla; con nubes vanilla no hay tal control, asi que la corrida se marca en el CSV para
     * que nadie compare peras con manzanas mas adelante.
     */
    public enum Coverage { DEFAULT, CLEAR, MAX }

    private final String id;
    private final Altitude altitude;
    private final Movement movement;
    private final Coverage coverage;

    BenchmarkScenario(String id, Altitude altitude, Movement movement, Coverage coverage) {
        this.id = id;
        this.altitude = altitude;
        this.movement = movement;
        this.coverage = coverage;
    }

    public String id() {
        return this.id;
    }

    public Altitude altitude() {
        return this.altitude;
    }

    public Movement movement() {
        return this.movement;
    }

    public Coverage coverage() {
        return this.coverage;
    }

    public static BenchmarkScenario byId(String id) {
        for (BenchmarkScenario scenario : values()) {
            if (scenario.id.equalsIgnoreCase(id)) {
                return scenario;
            }
        }
        return null;
    }
}
