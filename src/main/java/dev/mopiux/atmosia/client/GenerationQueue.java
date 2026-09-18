package dev.mopiux.atmosia.client;

import dev.mopiux.atmosia.core.RegionKey;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cola de generación diferida (Secciones 8.1 y 9.4).
 *
 * Los hilos de trabajo calculan densidad; el hilo de render recoge los resultados y arma la malla
 * dentro del presupuesto del frame. El reparto es el que exige el documento: el trabajo pesado se
 * distribuye y ninguna actualización congela el juego.
 *
 * El orden de prioridad lo impone quien envía, que recorre las regiones de más urgente a menos y
 * deja de enviar cuando llena la cola. Una cola con prioridad interna no ayudaría: la prioridad
 * cambia cada vez que el jugador gira la cabeza, así que lo correcto es reevaluarla cada frame y
 * mantener poca cosa en vuelo.
 */
public final class GenerationQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    /** Tope de trabajos en vuelo. Más que esto solo genera trabajo que la prioridad ya descartó. */
    private static final int MAX_IN_FLIGHT = 24;

    private final ExecutorService executor;
    private final Set<RegionKey> inFlight = ConcurrentHashMap.newKeySet();
    private final Queue<DensityJob.Result> completed = new ConcurrentLinkedQueue<>();

    public GenerationQueue(int threads) {
        int count = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "atmosia-gen-" + counter.incrementAndGet());
            thread.setDaemon(true);
            // Por debajo de lo normal: generar nubes nunca debe competir con el hilo de render.
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(count, factory);
        LOGGER.debug("Cola de generación con {} hilos", count);
    }

    /** Si la región ya está siendo calculada. */
    public boolean isInFlight(RegionKey key) {
        return this.inFlight.contains(key);
    }

    public boolean isSaturated() {
        return this.inFlight.size() >= MAX_IN_FLIGHT;
    }

    public int inFlightCount() {
        return this.inFlight.size();
    }

    /** Envía un trabajo. Devuelve false si ya estaba en vuelo o la cola está saturada. */
    public boolean submit(DensityJob job) {
        if (this.isSaturated() || !this.inFlight.add(job.key())) {
            return false;
        }
        try {
            this.executor.execute(() -> {
                try {
                    this.completed.add(job.compute());
                } catch (RuntimeException e) {
                    LOGGER.error("Falló la generación de {}", job.key(), e);
                } finally {
                    this.inFlight.remove(job.key());
                }
            });
            return true;
        } catch (RuntimeException e) {
            this.inFlight.remove(job.key());
            return false;
        }
    }

    /** Siguiente resultado listo, o null. Solo lo llama el hilo de render. */
    @Nullable
    public DensityJob.Result poll() {
        return this.completed.poll();
    }

    /** Descarta lo pendiente. Se usa al cambiar de mundo o de configuración. */
    public void clear() {
        this.completed.clear();
    }

    public void shutdown() {
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.completed.clear();
        this.inFlight.clear();
    }
}
