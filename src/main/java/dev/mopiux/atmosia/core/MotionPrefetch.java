package dev.mopiux.atmosia.core;

/**
 * Anticipa hacia dónde va la cámara, para generar antes las regiones que va a necesitar.
 *
 * La cola de prioridad ordena por distancia a la posición <em>actual</em>. A pie eso alcanza: se
 * camina a 4-5 bloques por segundo y una región mide 256, así que hay un minuto largo de margen.
 * Con elytra a 40-60 b/s el margen se achica, y la cola no se entera de que va a necesitar una
 * región hasta que el jugador ya está sobre ella.
 *
 * La corrección es barata: ordenar por distancia a la posición <em>proyectada</em>, sumándole a la
 * actual la velocidad por un horizonte de tiempo. A baja velocidad la proyección casi no se mueve
 * de la posición actual y el comportamiento converge al de siempre, así que el caso ya validado no
 * puede empeorar.
 *
 * Esta clase solo calcula el adelanto. Qué se hace con él —qué se genera y con qué prioridad— es
 * del renderer, y qué se dibuja sigue decidiéndose con la posición real: adelantar el dibujo
 * movería el domo respecto de la cámara y dejaría un borde visible por detrás.
 */
public final class MotionPrefetch {

    /** Cuánto tiempo hacia adelante se proyecta. */
    public static final double HORIZON_SECONDS = 1.5D;

    /**
     * Tope del adelanto, en bloques: una región.
     *
     * Es el límite duro por si algo produce una velocidad alta pero todavía creíble. Se alcanza a
     * partir de unos 171 b/s, que ningún movimiento normal sostiene.
     */
    public static final double MAX_LEAD = 256.0D;

    /**
     * Velocidad por encima de la cual la muestra se descarta en vez de acotarse.
     *
     * Un teletransporte, un cambio de dimensión o un frame perdido producen un salto de posición
     * que dividido por el delta de tiempo da una velocidad absurda. Acotar el adelanto no alcanza:
     * daría 256 bloques de adelanto en una dirección que no significa nada, y el sistema se pondría
     * a generar regiones donde el jugador no va a ir. Elytra con cohetes ronda los 60-70 b/s, así
     * que 200 deja margen de sobra para cualquier movimiento real, incluidos los de otros mods.
     */
    public static final double MAX_PLAUSIBLE_SPEED = 200.0D;

    /**
     * Por debajo de esta velocidad no se adelanta nada.
     *
     * Caminar da unos 4,3 b/s y correr unos 5,6. Adelantar ahí no aporta —sobra margen— y en cambio
     * haría que el mínimo movimiento reordenara la cola.
     */
    public static final double MIN_SPEED = 8.0D;

    /** Suavizado exponencial de la velocidad. Un solo frame raro no debe mover la proyección. */
    private static final double SMOOTHING = 0.2D;

    /** Delta de tiempo máximo admitido entre muestras. Más que esto es una pausa, no movimiento. */
    private static final double MAX_DELTA_SECONDS = 0.5D;

    private double velocityX;
    private double velocityZ;
    private double lastX;
    private double lastZ;
    private double lastSeconds;
    private boolean started;

    /** Registra la posición de este frame y actualiza la velocidad estimada. */
    public void update(double x, double z, double seconds) {
        if (!this.started) {
            this.started = true;
            this.lastX = x;
            this.lastZ = z;
            this.lastSeconds = seconds;
            return;
        }

        double dt = seconds - this.lastSeconds;
        this.lastSeconds = seconds;

        if (dt <= 0.0D || dt > MAX_DELTA_SECONDS) {
            // El juego estuvo en pausa, o el tiempo del mundo saltó. La posición se acepta como
            // nueva referencia pero no se deduce ninguna velocidad de ella.
            this.lastX = x;
            this.lastZ = z;
            this.velocityX = 0.0D;
            this.velocityZ = 0.0D;
            return;
        }

        double sampleX = (x - this.lastX) / dt;
        double sampleZ = (z - this.lastZ) / dt;
        this.lastX = x;
        this.lastZ = z;

        if (Math.sqrt(sampleX * sampleX + sampleZ * sampleZ) > MAX_PLAUSIBLE_SPEED) {
            // No es movimiento: es un teletransporte, un cambio de dimensión o un frame perdido.
            // La posición nueva ya quedó como referencia; la velocidad se descarta entera.
            this.velocityX = 0.0D;
            this.velocityZ = 0.0D;
            return;
        }

        this.velocityX += (sampleX - this.velocityX) * SMOOTHING;
        this.velocityZ += (sampleZ - this.velocityZ) * SMOOTHING;
    }

    /** Vuelve al estado inicial. Al cambiar de mundo o de dimensión. */
    public void reset() {
        this.started = false;
        this.velocityX = 0.0D;
        this.velocityZ = 0.0D;
    }

    public double speed() {
        return Math.sqrt(this.velocityX * this.velocityX + this.velocityZ * this.velocityZ);
    }

    /** Adelanto en X, en bloques. Cero si la velocidad no lo justifica. */
    public double leadX() {
        return this.velocityX * this.horizonFactor();
    }

    /** Adelanto en Z, en bloques. */
    public double leadZ() {
        return this.velocityZ * this.horizonFactor();
    }

    /** Longitud del adelanto, en bloques. Es cuánto hay que agrandar el radio de barrido. */
    public double leadLength() {
        double x = this.leadX();
        double z = this.leadZ();
        return Math.sqrt(x * x + z * z);
    }

    /**
     * Segundos efectivos de proyección, ya con el mínimo y el tope aplicados.
     *
     * Devolver un factor en vez de recortar cada eje por separado mantiene la dirección intacta:
     * recortar X y Z de a uno torcería el adelanto en diagonal.
     */
    private double horizonFactor() {
        double speed = this.speed();
        if (speed < MIN_SPEED) {
            return 0.0D;
        }
        double lead = speed * HORIZON_SECONDS;
        if (lead <= MAX_LEAD) {
            return HORIZON_SECONDS;
        }
        return MAX_LEAD / speed;
    }
}
