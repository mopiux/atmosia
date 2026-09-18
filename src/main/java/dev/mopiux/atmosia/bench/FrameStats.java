package dev.mopiux.atmosia.bench;

import java.util.Arrays;

/**
 * Acumulador de tiempos de frame.
 *
 * Guarda cada muestra en vez de promediar al vuelo, porque el 1% low —que es la métrica que
 * delata los tirones— necesita la distribución completa, no un promedio. A 300 FPS durante 60
 * segundos son 18.000 doubles: 144 KB, irrelevante.
 */
public final class FrameStats {

    private double[] samplesMs = new double[4096];
    private int count;

    public void add(double millis) {
        if (this.count == this.samplesMs.length) {
            this.samplesMs = Arrays.copyOf(this.samplesMs, this.samplesMs.length * 2);
        }
        this.samplesMs[this.count++] = millis;
    }

    public void reset() {
        this.count = 0;
    }

    public int count() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public double meanMs() {
        if (this.count == 0) {
            return Double.NaN;
        }
        double total = 0.0D;
        for (int i = 0; i < this.count; i++) {
            total += this.samplesMs[i];
        }
        return total / this.count;
    }

    /**
     * Percentil por interpolación nearest-rank sobre una copia ordenada.
     *
     * @param percentile entre 0 y 100. El p99 del tiempo de frame es el 1% low de FPS.
     */
    public double percentileMs(double percentile) {
        if (this.count == 0) {
            return Double.NaN;
        }
        double[] sorted = Arrays.copyOf(this.samplesMs, this.count);
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0D * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** FPS promedio derivado del tiempo medio de frame. */
    public double meanFps() {
        double mean = this.meanMs();
        return mean > 0.0D ? 1000.0D / mean : Double.NaN;
    }

    /**
     * 1% low: el FPS equivalente al promedio del 1% de frames más lentos.
     *
     * No es lo mismo que {@code 1000 / percentileMs(99)}, y la diferencia importa. El percentil 99
     * por rango más cercano devuelve el valor en la posición 99%, que en una muestra de 101 frames
     * con un solo tirón de 100 ms deja ese tirón afuera y reporta 100 FPS. El promedio del peor 1%
     * —que es lo que reportan las herramientas de benchmarking cuando dicen "1% low"— lo captura y
     * reporta 10 FPS. Medir tirones y no verlos es peor que no medirlos.
     */
    public double onePercentLowFps() {
        double worst = this.meanOfSlowestFractionMs(0.01D);
        return worst > 0.0D ? 1000.0D / worst : Double.NaN;
    }

    /**
     * Tiempo medio del {@code fraction} más lento de los frames, siempre sobre al menos uno.
     *
     * @param fraction entre 0 y 1. 0.01 es el 1% low.
     */
    public double meanOfSlowestFractionMs(double fraction) {
        if (this.count == 0) {
            return Double.NaN;
        }
        double[] sorted = Arrays.copyOf(this.samplesMs, this.count);
        Arrays.sort(sorted);
        int taken = Math.max(1, (int) Math.floor(this.count * fraction));
        double total = 0.0D;
        for (int i = sorted.length - taken; i < sorted.length; i++) {
            total += sorted[i];
        }
        return total / taken;
    }

    /** Copia de las muestras, para el volcado por frame. */
    public double[] samples() {
        return Arrays.copyOf(this.samplesMs, this.count);
    }
}
