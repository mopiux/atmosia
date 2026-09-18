package dev.mopiux.atmosia.bench;

import java.util.Locale;

/**
 * Comprobaciones de la parte del harness que no depende de Minecraft: estadísticas de frame,
 * formato del CSV y trayectorias de cámara.
 *
 * Es un {@code main} y no JUnit a propósito: corre sin dependencias ni Gradle, que es lo único
 * posible mientras el entorno no pueda descargar Forge. Desde la raíz del repositorio:
 *
 * <pre>
 * javac --release 17 -d /tmp/atmosia-test \
 *     src/main/java/dev/mopiux/atmosia/bench/CameraPath.java \
 *     src/main/java/dev/mopiux/atmosia/bench/BenchmarkScenario.java \
 *     src/main/java/dev/mopiux/atmosia/bench/FrameStats.java \
 *     src/main/java/dev/mopiux/atmosia/bench/CsvReporter.java \
 *     src/test/java/dev/mopiux/atmosia/bench/BenchSmokeTest.java
 * java -cp /tmp/atmosia-test dev.mopiux.atmosia.bench.BenchSmokeTest
 * </pre>
 *
 * Sale con código 1 si algo falla, así que sirve tal cual en un script de CI.
 */
public final class BenchSmokeTest {
    static int fails = 0;
    static void check(String name, boolean ok, Object got) {
        System.out.printf(Locale.ROOT, "%-46s %s  (%s)%n", name, ok ? "OK" : "FALLA", got);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        // 100 frames de 10 ms + 1 tirón de 100 ms: el caso que separa el 1% low del percentil 99.
        FrameStats s = new FrameStats();
        for (int i = 0; i < 100; i++) s.add(10.0);
        s.add(100.0);
        check("count", s.count() == 101, s.count());
        check("media ~10.89 ms", Math.abs(s.meanMs() - 10.891) < 0.01, s.meanMs());
        check("FPS medio ~91.8", Math.abs(s.meanFps() - 91.82) < 0.1, s.meanFps());
        // El percentil 99 por rango mas cercano NO ve el tiron en 101 muestras: es correcto y es
        // justamente por eso que el 1% low no puede calcularse a partir de el.
        check("p99 (rango mas cercano) = 10 ms", s.percentileMs(99.0) == 10.0, s.percentileMs(99.0));
        check("1% low SI captura el tiron = 10 FPS", Math.abs(s.onePercentLowFps() - 10.0) < 1e-9, s.onePercentLowFps());
        check("peor 1% = 100 ms", s.meanOfSlowestFractionMs(0.01) == 100.0, s.meanOfSlowestFractionMs(0.01));
        check("p100 = max", s.percentileMs(100.0) == 100.0, s.percentileMs(100.0));
        check("p50 = 10 ms", s.percentileMs(50.0) == 10.0, s.percentileMs(50.0));

        // Muestra grande: el 1% de 10000 son los 100 peores, promediados.
        FrameStats many = new FrameStats();
        for (int i = 0; i < 9900; i++) many.add(10.0);
        for (int i = 0; i < 100; i++) many.add(50.0);
        check("1% de 10000 = los 100 peores", many.meanOfSlowestFractionMs(0.01) == 50.0, many.meanOfSlowestFractionMs(0.01));
        check("1% low de 10000 = 20 FPS", Math.abs(many.onePercentLowFps() - 20.0) < 1e-9, many.onePercentLowFps());

        FrameStats one = new FrameStats();
        one.add(33.0);
        check("una sola muestra no divide por cero", Math.abs(one.onePercentLowFps() - 1000.0/33.0) < 1e-9, one.onePercentLowFps());

        FrameStats empty = new FrameStats();
        check("vacio no explota", Double.isNaN(empty.meanMs()) && empty.isEmpty()
              && Double.isNaN(empty.onePercentLowFps()), empty.meanMs());
        s.reset();
        check("reset limpia", s.isEmpty(), s.count());

        // Crecimiento del array por encima de la capacidad inicial (4096).
        FrameStats big = new FrameStats();
        for (int i = 0; i < 10_000; i++) big.add(i % 7 + 1);
        check("crece mas alla de 4096", big.count() == 10_000, big.count());

        // Formato: NaN -> celda vacia, -1 -> celda vacia, punto decimal siempre.
        check("NaN -> vacio", CsvReporter.num(Double.NaN).isEmpty(), "[" + CsvReporter.num(Double.NaN) + "]");
        check("-1L -> vacio", CsvReporter.num(-1L).isEmpty(), "[" + CsvReporter.num(-1L) + "]");
        check("0L -> \"0\" (no vacio)", CsvReporter.num(0L).equals("0"), CsvReporter.num(0L));
        check("decimal con punto", CsvReporter.num(16.6667).equals("16.6667"), CsvReporter.num(16.6667));
        check("sin coma en el numero", !CsvReporter.num(1234.5).contains(","), CsvReporter.num(1234.5));

        // Trayectoria: determinista y consistente entre escenarios.
        CameraPath spin = new CameraPath(BenchmarkScenario.CAMERA_SPIN, 192.0);
        check("misma t -> misma pose", spin.poseAt(3.7).equals(spin.poseAt(3.7)), spin.poseAt(3.7));
        check("yaw acotado a [-180,180]", Math.abs(spin.poseAt(97.3).yaw()) <= 180.0f, spin.poseAt(97.3).yaw());
        check("giro avanza con el tiempo", spin.poseAt(0.0).yaw() != spin.poseAt(1.0).yaw(), spin.poseAt(1.0).yaw());

        CameraPath flight = new CameraPath(BenchmarkScenario.FAST_TRAVEL, 192.0);
        check("vuelo avanza 40 b/s", Math.abs(flight.poseAt(10.0).x() - flight.poseAt(0.0).x() - 400.0) < 1e-9,
              flight.poseAt(10.0).x());

        CameraPath inside = new CameraPath(BenchmarkScenario.INSIDE_CLOUDS, 192.0);
        CameraPath above = new CameraPath(BenchmarkScenario.ABOVE_CLOUDS, 192.0);
        CameraPath below = new CameraPath(BenchmarkScenario.BELOW_CLOUDS, 192.0);
        check("debajo < dentro < encima", below.poseAt(0).y() < inside.poseAt(0).y()
              && inside.poseAt(0).y() < above.poseAt(0).y(),
              below.poseAt(0).y() + " / " + inside.poseAt(0).y() + " / " + above.poseAt(0).y());

        CameraPath sweep = new CameraPath(BenchmarkScenario.ALTITUDE_SWEEP, 192.0);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double t = 0; t < 8.0; t += 0.01) { double y = sweep.poseAt(t).y(); min = Math.min(min, y); max = Math.max(max, y); }
        check("barrido cruza la capa en ambos sentidos", min < 192.0 && max > 192.0, min + " .. " + max);

        check("escenarios: 9 y lookup por id", BenchmarkScenario.values().length == 9
              && BenchmarkScenario.byId("inside_clouds") == BenchmarkScenario.INSIDE_CLOUDS
              && BenchmarkScenario.byId("no_existe") == null, BenchmarkScenario.values().length);

        System.out.println(fails == 0 ? "\nTODO OK" : "\n" + fails + " FALLAS");
        System.exit(fails == 0 ? 0 : 1);
    }
}
