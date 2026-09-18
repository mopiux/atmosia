package dev.mopiux.atmosia.client;

import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.DensityField;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.RegionKey;

/**
 * Cálculo del campo de densidad de una región, para ejecutar fuera del hilo de render.
 *
 * Es deliberadamente pura: recibe seed, capa y región, y devuelve un arreglo. No toca Minecraft,
 * no toca OpenGL y no comparte estado mutable, así que es segura en cualquier hilo. Esa es la
 * mitad del modelo de concurrencia de la Sección 9.4 que puede paralelizarse; la construcción de
 * la malla y la subida a GPU se quedan en el hilo de render, donde vive el contexto de OpenGL.
 */
public final class DensityJob {

    private final RegionKey key;
    private final CloudLayerDef layer;
    private final LodLevel lod;
    private final NoiseField noise;
    private final double coverageScale;

    public DensityJob(RegionKey key, CloudLayerDef layer, LodLevel lod, NoiseField noise) {
        this(key, layer, lod, noise, 1.0D);
    }

    public DensityJob(RegionKey key, CloudLayerDef layer, LodLevel lod, NoiseField noise,
                      double coverageScale) {
        this.key = key;
        this.layer = layer;
        this.lod = lod;
        this.noise = noise;
        this.coverageScale = coverageScale;
    }

    public double coverageScale() {
        return this.coverageScale;
    }

    public RegionKey key() {
        return this.key;
    }

    public CloudLayerDef layer() {
        return this.layer;
    }

    public LodLevel lod() {
        return this.lod;
    }

    /**
     * Calcula la densidad celda por celda.
     *
     * Se muestrea en el centro de cada celda y en coordenadas locales a la región, nunca absolutas:
     * es la estrategia de floating origin de la Sección 4, y es lo que mantiene el ruido estable a
     * millones de bloques del origen.
     */
    public Result compute() {
        int cells = this.lod.cellsPerSide(RegionKey.REGION_SIZE);
        float[] density = new float[cells * cells];
        DensityField field = new DensityField(this.noise, this.layer, this.coverageScale);

        double originX = this.key.originX();
        double originZ = this.key.originZ();
        double cellSize = this.lod.cellSize();

        int nonEmpty = 0;
        for (int cz = 0; cz < cells; cz++) {
            double z = originZ + (cz + 0.5D) * cellSize;
            for (int cx = 0; cx < cells; cx++) {
                double x = originX + (cx + 0.5D) * cellSize;
                float value = (float) field.densityAt(x, z);
                density[cz * cells + cx] = value;
                if (value > 0.0F) {
                    nonEmpty++;
                }
            }
        }
        return new Result(this, density, cells, nonEmpty);
    }

    /** Resultado listo para que el hilo de render arme la malla. */
    public record Result(DensityJob job, float[] density, int cellsPerSide, int nonEmptyCells) {

        public boolean isEmpty() {
            return this.nonEmptyCells == 0;
        }

        /** Cota superior de cuádruples, para consultar el presupuesto antes de construir. */
        public int estimatedQuads() {
            return this.nonEmptyCells * this.job.lod().slices();
        }
    }
}
