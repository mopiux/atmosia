package dev.mopiux.atmosia.bench;

import dev.mopiux.atmosia.AtmosiaConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Corrida de benchmark: máquina de estados que fija las condiciones, recorre una trayectoria
 * determinista, acumula tiempos y escribe el resultado.
 *
 * Implementa la Sección 16.2 del documento de diseño. El orden importa:
 * condiciones fijas → calentamiento descartado → ventana de medición → restaurar todo.
 *
 * SIN COMPILAR NI EJECUTAR: el entorno donde se escribió no tiene Minecraft ni Forge. Los puntos
 * donde la API de 1.20.1 hay que confirmarla están marcados con VERIFICAR.
 */
public final class BenchmarkRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia-bench");
    private static final BenchmarkRunner INSTANCE = new BenchmarkRunner();

    /** Segundos descartados al principio: caché fría y shaders sin compilar mienten. */
    private static final double DEFAULT_WARMUP_SECONDS = 5.0D;

    /** Duración de la ventana de medición. */
    private static final double DEFAULT_DURATION_SECONDS = 30.0D;

    private enum State { IDLE, WARMUP, RECORDING }

    private State state = State.IDLE;
    private BenchmarkScenario scenario;
    private CameraPath path;
    private FrameStats cpuStats = new FrameStats();
    private FrameStats gpuStats = new FrameStats();
    private List<double[]> perFrame = new ArrayList<>();
    @Nullable
    private GpuTimer gpuTimer;

    private long runStartNanos;
    private long lastFrameStartNanos;
    private double warmupSeconds = DEFAULT_WARMUP_SECONDS;
    private double durationSeconds = DEFAULT_DURATION_SECONDS;

    /** Ajustes del jugador que la corrida pisa y devuelve al terminar. */
    private boolean savedVsync;
    private int savedFramerateLimit;

    private BenchmarkRunner() {
    }

    public static BenchmarkRunner get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return this.state != State.IDLE;
    }

    // -------------------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------------------

    public boolean start(BenchmarkScenario scenario, double warmup, double duration) {
        Minecraft mc = Minecraft.getInstance();
        if (this.isRunning() || mc.level == null || mc.player == null) {
            return false;
        }

        this.scenario = scenario;
        this.warmupSeconds = warmup;
        this.durationSeconds = duration;
        this.path = new CameraPath(scenario, CameraPath.VANILLA_CLOUD_HEIGHT);
        this.cpuStats = new FrameStats();
        this.gpuStats = new FrameStats();
        this.perFrame = new ArrayList<>();
        this.gpuTimer = new GpuTimer();

        this.applyFixedConditions(mc);

        this.state = State.WARMUP;
        this.runStartNanos = System.nanoTime();
        this.lastFrameStartNanos = 0L;

        say(mc, "Benchmark iniciado: " + scenario.id()
                + " (calentamiento " + warmup + " s, medición " + duration + " s)"
                + (this.gpuTimer.isAvailable() ? "" : " — sin medición de GPU en este driver"));
        return true;
    }

    public void abort(String reason) {
        if (!this.isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        this.restoreConditions(mc);
        this.state = State.IDLE;
        this.disposeTimer();
        say(mc, "Benchmark cancelado: " + reason);
    }

    // -------------------------------------------------------------------------------------
    // Por frame
    // -------------------------------------------------------------------------------------

    /** Comienzo del frame: mueve la cámara y abre la consulta de GPU. */
    public void onFrameStart() {
        if (!this.isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            this.abort("el mundo dejó de estar cargado");
            return;
        }

        double elapsed = this.elapsedSeconds();
        this.applyPose(mc.player, this.path.poseAt(elapsed));

        if (this.gpuTimer != null) {
            this.gpuTimer.beginFrame();
        }

        long now = System.nanoTime();
        if (this.lastFrameStartNanos != 0L && this.state == State.RECORDING) {
            double cpuMs = (now - this.lastFrameStartNanos) / 1_000_000.0D;
            double gpuMs = this.gpuTimer != null ? this.gpuTimer.lastResultMillis() : Double.NaN;
            this.cpuStats.add(cpuMs);
            if (!Double.isNaN(gpuMs)) {
                this.gpuStats.add(gpuMs);
            }
            this.perFrame.add(new double[] { cpuMs, gpuMs });
        }
        this.lastFrameStartNanos = now;
    }

    /** Fin del frame: cierra la consulta de GPU y evalúa las transiciones de estado. */
    public void onFrameEnd() {
        if (!this.isRunning()) {
            return;
        }
        if (this.gpuTimer != null) {
            this.gpuTimer.endFrame();
        }

        double elapsed = this.elapsedSeconds();
        if (this.state == State.WARMUP && elapsed >= this.warmupSeconds) {
            // Arrancar la ventana limpia: lo acumulado durante el calentamiento se tira.
            this.state = State.RECORDING;
            this.cpuStats.reset();
            this.gpuStats.reset();
            this.perFrame.clear();
            this.runStartNanos = System.nanoTime();
            this.lastFrameStartNanos = 0L;
            say(Minecraft.getInstance(), "Calentamiento terminado, midiendo.");
        } else if (this.state == State.RECORDING && elapsed >= this.durationSeconds) {
            this.finish();
        }
    }

    private void finish() {
        Minecraft mc = Minecraft.getInstance();
        this.state = State.IDLE;
        this.restoreConditions(mc);

        if (this.cpuStats.isEmpty()) {
            this.disposeTimer();
            say(mc, "Benchmark terminado sin muestras. Algo salió mal.");
            return;
        }

        String row = this.buildSummaryRow(mc);
        CsvReporter reporter = new CsvReporter(mc.gameDirectory.toPath().resolve("atmosia-benchmarks"));
        try {
            Path summary = reporter.appendSummary(row);
            reporter.writeFrames(this.scenario.id(), this.perFrame);
            say(mc, String.format(Locale.ROOT,
                    "Benchmark %s: %.1f FPS promedio, %.1f FPS 1%% low, %.2f ms CPU. Resultados en %s",
                    this.scenario.id(), this.cpuStats.meanFps(), this.cpuStats.onePercentLowFps(),
                    this.cpuStats.meanMs(), summary));
        } catch (IOException e) {
            LOGGER.error("No se pudo escribir el resultado del benchmark", e);
            say(mc, "Benchmark terminado, pero falló la escritura del CSV: " + e.getMessage());
        }
        this.disposeTimer();
    }

    // -------------------------------------------------------------------------------------
    // Condiciones fijas
    // -------------------------------------------------------------------------------------

    /**
     * Fija lo que tiene que ser igual en cada corrida. Sin esto, dos mediciones no son
     * comparables y el criterio de aceptación no significa nada.
     *
     * VERIFICAR: los nombres de las opciones y de sendCommand contra la API real de 1.20.1.
     */
    private void applyFixedConditions(Minecraft mc) {
        this.savedVsync = mc.options.enableVsync().get();
        this.savedFramerateLimit = mc.options.framerateLimit().get();

        // Con VSync o con límite de FPS, el número de FPS mide el monitor, no el renderer.
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.options.save();

        LocalPlayer player = mc.player;
        if (player != null && player.connection != null) {
            // Espectador: sin colisión ni física, la posición impuesta por la trayectoria se
            // respeta en vez de pelearse con el servidor.
            player.connection.sendCommand("gamemode spectator");
            player.connection.sendCommand("gamerule doDaylightCycle false");
            player.connection.sendCommand("gamerule doWeatherCycle false");
            player.connection.sendCommand("time set 6000");
            player.connection.sendCommand("weather clear");
        }
    }

    private void restoreConditions(Minecraft mc) {
        mc.options.enableVsync().set(this.savedVsync);
        mc.options.framerateLimit().set(this.savedFramerateLimit);
        mc.options.save();
    }

    private void applyPose(LocalPlayer player, CameraPath.Pose pose) {
        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(pose.x(), pose.y(), pose.z());
        player.setYRot(pose.yaw());
        player.setXRot(pose.pitch());
        player.setYHeadRot(pose.yaw());
    }

    // -------------------------------------------------------------------------------------

    private String buildSummaryRow(Minecraft mc) {
        CloudMetricsProvider provider = CloudMetricsProvider.Registry.get();
        String renderer = provider != null ? provider.rendererName() : "vanilla";
        String notes = provider != null ? "" : "linea base sin renderer propio";

        return String.join(",",
                CsvReporter.timestamp(),
                this.scenario.id(),
                renderer,
                this.scenario.coverage().name().toLowerCase(Locale.ROOT),
                AtmosiaConfig.CLIENT.cloudMode.get().name().toLowerCase(Locale.ROOT),
                AtmosiaConfig.CLIENT.qualityProfile.get().name().toLowerCase(Locale.ROOT),
                CsvReporter.num(AtmosiaConfig.CLIENT.coverageScale.get()),
                CsvReporter.num(this.durationSeconds),
                Integer.toString(this.cpuStats.count()),
                CsvReporter.num(this.cpuStats.meanFps()),
                CsvReporter.num(this.cpuStats.onePercentLowFps()),
                CsvReporter.num(this.cpuStats.meanMs()),
                CsvReporter.num(this.cpuStats.percentileMs(95.0D)),
                CsvReporter.num(this.cpuStats.percentileMs(99.0D)),
                CsvReporter.num(this.cpuStats.percentileMs(100.0D)),
                CsvReporter.num(this.gpuStats.isEmpty() ? Double.NaN : this.gpuStats.meanMs()),
                Integer.toString(mc.getWindow().getWidth()),
                Integer.toString(mc.getWindow().getHeight()),
                Integer.toString(mc.options.renderDistance().get()),
                mc.options.getCloudsType().name().toLowerCase(Locale.ROOT),
                CsvReporter.num(provider != null ? provider.activeRegions() : -1L),
                CsvReporter.num(provider != null ? provider.queuedRegions() : -1L),
                CsvReporter.num(provider != null ? provider.verticesLastFrame() : -1L),
                CsvReporter.num(provider != null ? provider.drawCallsLastFrame() : -1L),
                CsvReporter.num(provider != null ? provider.cacheBytes() : -1L),
                CsvReporter.num(provider != null ? provider.gpuBytes() : -1L),
                CsvReporter.num(provider != null ? provider.lastRegionGenerationMillis() : Double.NaN),
                notes);
    }

    private double elapsedSeconds() {
        return (System.nanoTime() - this.runStartNanos) / 1_000_000_000.0D;
    }

    private void disposeTimer() {
        if (this.gpuTimer != null) {
            this.gpuTimer.dispose();
            this.gpuTimer = null;
        }
    }

    private static void say(Minecraft mc, String message) {
        LOGGER.info(message);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Atmosia] " + message), false);
        }
    }
}
