package dev.mopiux.atmosia.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Escritura de resultados a CSV.
 *
 * Dos archivos por diseño:
 * - {@code results.csv}: una fila por corrida, acumulativo. Es el que se compara entre fases.
 * - {@code frames-<escenario>-<timestamp>.csv}: una fila por frame de esa corrida. Permite
 *   recalcular percentiles y ver dónde estuvieron los tirones, en vez de confiar en un promedio.
 *
 * Todos los números se formatean con {@link Locale#ROOT} a propósito: con locale del sistema, una
 * máquina en español escribe "16,7" y rompe el CSV para cualquiera que lo abra después.
 */
public final class CsvReporter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static final String HEADER = String.join(",",
            "timestamp", "scenario", "renderer", "coverage",
            "duration_s", "frames",
            "avg_fps", "low_1pct_fps",
            "cpu_frame_avg_ms", "cpu_frame_p95_ms", "cpu_frame_p99_ms", "cpu_frame_max_ms",
            "gpu_frame_avg_ms",
            "screen_width", "screen_height", "render_distance", "cloud_status",
            "active_regions", "queued_regions", "vertices", "draw_calls",
            "cache_bytes", "gpu_bytes", "region_gen_ms",
            "notes");

    private final Path directory;

    public CsvReporter(Path directory) {
        this.directory = directory;
    }

    /** Agrega una fila de resumen, creando el archivo con encabezado si no existía. */
    public Path appendSummary(String csvRow) throws IOException {
        Files.createDirectories(this.directory);
        Path file = this.directory.resolve("results.csv");
        boolean isNew = !Files.exists(file);
        StringBuilder out = new StringBuilder();
        if (isNew) {
            out.append(HEADER).append('\n');
        }
        out.append(csvRow).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    /** Vuelca los tiempos frame a frame de una corrida. */
    public Path writeFrames(String scenarioId, List<double[]> frames) throws IOException {
        Files.createDirectories(this.directory);
        Path file = this.directory.resolve(
                "frames-" + scenarioId + "-" + LocalDateTime.now().format(STAMP) + ".csv");
        StringBuilder out = new StringBuilder("frame,cpu_ms,gpu_ms\n");
        for (int i = 0; i < frames.size(); i++) {
            double[] sample = frames.get(i);
            out.append(i).append(',')
               .append(num(sample[0])).append(',')
               .append(num(sample[1])).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    /** NaN se escribe como celda vacía: vacío es "no medido", que no es lo mismo que cero. */
    public static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
    }

    /** -1 es la convención de "no aplica" de {@link CloudMetricsProvider}. */
    public static String num(long value) {
        return value < 0L ? "" : Long.toString(value);
    }

    public static String timestamp() {
        return LocalDateTime.now().format(STAMP);
    }
}
