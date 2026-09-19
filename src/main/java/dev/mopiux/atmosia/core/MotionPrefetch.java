package dev.mopiux.atmosia.core;

/**
 * Anticipa hacia donde va la camara, para generar antes las regiones que va a necesitar.
 *
 * La cola de prioridad ordena por distancia a la posicion <em>actual</em>. A pie eso alcanza: se
 * camina a 4-5 bloques por segundo y una region mide 256, asi que hay un minuto largo de margen.
 * Con elytra a 40-60 b/s el margen se achica, y la cola no se entera de que va a necesitar una
 * region hasta que el jugador ya esta sobre ella.
 *
 * La correccion es barata: ordenar por distancia a la posicion <em>proyectada</em>, sumandole a la
 * actual la velocidad por un horizonte de tiempo. A baja velocidad la proyeccion casi no se mueve
 * de la posicion actual y el comportamiento converge al de siempre, asi que el caso ya validado no
 * puede empeorar.
 *
 * Esta clase solo calcula el adelanto. Que se hace con el -que se genera y con que prioridad- es
 * del renderer, y que se dibuja sigue decidiendose con la posicion real: adelantar el dibujo
 * moveria el domo respecto de la camara y dejaria un borde visible por detras.
 */
public final class MotionPrefetch {

    /** Cuanto tiempo hacia adelante se proyecta. */
    public static final double HORIZON_SECONDS = 1.5D;

    /**
     * Tope del adelanto, en bloques: una region.
     *
     * Es el limite duro por si algo produce una velocidad alta pero todavia creible. Se alcanza a
     * partir de unos 171 b/s, que ningun movimiento normal sostiene.
     */
    public static final double MAX_LEAD = 256.0D;

    /**
     * Velocidad por encima de la cual la muestra se descarta en vez de acotarse.
     *
     * Un teletransporte, un cambio de dimension o un frame perdido producen un salto de posicion
     * que dividido por el delta de tiempo da una velocidad absurda. Acotar el adelanto no alcanza:
     * daria 256 bloques de adelanto en una direccion que no significa nada, y el sistema se pondria
     * a generar regiones donde el jugador no va a ir. Elytra con cohetes ronda los 60-70 b/s, asi
     * que 200 deja margen de sobra para cualquier movimiento real, incluidos los de otros mods.
     */
    public static final double MAX_PLAUSIBLE_SPEED = 200.0D;

    /**
     * Por debajo de esta velocidad no se adelanta nada.
     *
     * Caminar da unos 4,3 b/s y correr unos 5,6. Adelantar ahi no aporta -sobra margen- y en cambio
     * haria que el minimo movimiento reordenara la cola.
     */
    public static final double MIN_SPEED = 8.0D;

    /** Suavizado exponencial de la velocidad. Un solo frame raro no debe mover la proyeccion. */
    private static final double SMOOTHING = 0.2D;

    /** Delta de tiempo maximo admitido entre muestras. Mas que esto es una pausa, no movimiento. */
    private static final double MAX_DELTA_SECONDS = 0.5D;

    private double velocityX;
    private double velocityZ;
    private double lastX;
    private double lastZ;
    private double lastSeconds;
    private boolean started;

    /** Registra la posicion de este frame y actualiza la velocidad estimada. */
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
            // El juego estuvo en pausa, o el tiempo del mundo salto. La posicion se acepta como
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
            // No es movimiento: es un teletransporte, un cambio de dimension o un frame perdido.
            // La posicion nueva ya quedo como referencia; la velocidad se descarta entera.
            this.velocityX = 0.0D;
            this.velocityZ = 0.0D;
            return;
        }

        this.velocityX += (sampleX - this.velocityX) * SMOOTHING;
        this.velocityZ += (sampleZ - this.velocityZ) * SMOOTHING;
    }

    /** Vuelve al estado inicial. Al cambiar de mundo o de dimension. */
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

    /** Longitud del adelanto, en bloques. Es cuanto hay que agrandar el radio de barrido. */
    public double leadLength() {
        double x = this.leadX();
        double z = this.leadZ();
        return Math.sqrt(x * x + z * z);
    }

    /**
     * Segundos efectivos de proyeccion, ya con el minimo y el tope aplicados.
     *
     * Devolver un factor en vez de recortar cada eje por separado mantiene la direccion intacta:
     * recortar X y Z de a uno torceria el adelanto en diagonal.
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
