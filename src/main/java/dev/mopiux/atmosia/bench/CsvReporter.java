package dev.mopiux.atmosia.bench;

import java.io.BufferedReader;
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
 * Dos archivos por diseno:
 * - {@code results.csv}: una fila por corrida, acumulativo. Es el que se compara entre fases.
 * - {@code frames-<escenario>-<timestamp>.csv}: una fila por frame de esa corrida. Permite
 *   recalcular percentiles y ver donde estuvieron los tirones, en vez de confiar en un promedio.
 *
 * Todos los numeros se formatean con {@link Locale#ROOT} a proposito: con locale del sistema, una
 * maquina en espanol escribe "16,7" y rompe el CSV para cualquiera que lo abra despues.
 */
public final class CsvReporter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static final String HEADER = String.join(",",
            "timestamp", "scenario", "renderer", "coverage",
            // El perfil y la cantidad van en la fila: sin ellos, dos corridas del mismo escenario
            // no son comparables y no hay forma de saberlo despues.
            "cloud_mode", "quality_profile", "coverage_scale",
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

    /**
     * Agrega una fila de resumen, creando el archivo con encabezado si no existia.
     *
     * Si el archivo ya existe pero su encabezado es de una version anterior del mod, se aparta con
     * el nombre cambiado y se empieza uno nuevo. Agregar columnas nuevas debajo de un encabezado
     * viejo produce un archivo donde cada valor cae en la columna equivocada: se sigue abriendo sin
     * error y todo lo que se lea de el es mentira. Un archivo de mediciones que miente en silencio
     * es peor que no tenerlo, y paso de verdad: la tanda del 18/09 quedo corrida tres columnas.
     */
    public Path appendSummary(String csvRow) throws IOException {
        Files.createDirectories(this.directory);
        Path file = this.directory.resolve("results.csv");

        boolean writeHeader = true;
        if (Files.exists(file)) {
            String existing = firstLine(file);
            if (HEADER.equals(existing)) {
                writeHeader = false;
            } else {
                Files.move(file, this.rotatedName(file));
            }
        }

        StringBuilder out = new StringBuilder();
        if (writeHeader) {
            out.append(HEADER).append('\n');
        }
        out.append(csvRow).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    /** Primera linea del archivo, o cadena vacia si esta vacio o no se puede leer. */
    private static String firstLine(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            // No se pudo leer: se trata como encabezado distinto y el archivo se aparta. Prefiere
            // un archivo de mas a arriesgar filas corridas en el que ya esta.
            return "";
        }
    }

    /** Nombre libre para apartar el archivo viejo, sin pisar uno apartado antes. */
    private Path rotatedName(Path file) {
        String stamp = LocalDateTime.now().format(STAMP);
        Path candidate = this.directory.resolve("results-anterior-" + stamp + ".csv");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = this.directory.resolve("results-anterior-" + stamp + "-" + suffix + ".csv");
            suffix++;
        }
        return candidate;
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

    /** NaN se escribe como celda vacia: vacio es "no medido", que no es lo mismo que cero. */
    public static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
    }

    /** -1 es la convencion de "no aplica" de {@link CloudMetricsProvider}. */
    public static String num(long value) {
        return value < 0L ? "" : Long.toString(value);
    }

    public static String timestamp() {
        return LocalDateTime.now().format(STAMP);
    }
}
