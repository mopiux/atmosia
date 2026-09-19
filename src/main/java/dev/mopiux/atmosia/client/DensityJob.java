package dev.mopiux.atmosia.client;

import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.DensityField;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.RegionKey;

/**
 * Calculo del campo de densidad de una region, para ejecutar fuera del hilo de render.
 *
 * Es deliberadamente pura: recibe seed, capa y region, y devuelve un arreglo. No toca Minecraft,
 * no toca OpenGL y no comparte estado mutable, asi que es segura en cualquier hilo. Esa es la
 * mitad del modelo de concurrencia de la Seccion 9.4 que puede paralelizarse; la construccion de
 * la malla y la subida a GPU se quedan en el hilo de render, donde vive el contexto de OpenGL.
 */
public final class DensityJob {

    private final RegionKey key;
    private final CloudLayerDef layer;
    private final LodLevel lod;
    private final NoiseField noise;
    private final double coverageScale;
    private final boolean topDown;

    public DensityJob(RegionKey key, CloudLayerDef layer, LodLevel lod, NoiseField noise) {
        this(key, layer, lod, noise, 1.0D, true);
    }

    public DensityJob(RegionKey key, CloudLayerDef layer, LodLevel lod, NoiseField noise,
                      double coverageScale, boolean topDown) {
        this.key = key;
        this.layer = layer;
        this.lod = lod;
        this.noise = noise;
        this.coverageScale = coverageScale;
        this.topDown = topDown;
    }

    /**
     * Si los cortes se emiten del mas alto al mas bajo.
     *
     * Es lo que decide el orden en que la GPU los mezcla, y tiene que coincidir con el orden de
     * profundidad visto desde donde esta la camara. Ver {@code RegionMeshBuilder}.
     */
    public boolean topDown() {
        return this.topDown;
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
     * Calcula la densidad en las ESQUINAS de las celdas.
     *
     * Hasta la 0.2.4 se muestreaba el centro de cada celda y ese unico valor pintaba el
     * cuadrilatero entero con un alfa plano. Eso convierte cada celda en un rectangulo de borde
     * duro, y con ello la grilla queda a la vista en cuanto dos celdas vecinas difieren.
     *
     * Muestreando las esquinas, cada vertice lleva su propio alfa y el color se interpola por la
     * cara: la silueta de la nube pasa a ser continua en vez de escalonada. Cuesta una fila y una
     * columna mas de muestras por region -289 en vez de 256 en el nivel mas fino, un 13%- y no
     * cuesta nada en la GPU.
     *
     * Ademas cierra el borde entre regiones sin trabajo extra: la esquina derecha de la ultima
     * celda de una region cae exactamente sobre la esquina izquierda de la primera de su vecina,
     * misma coordenada de mundo y por lo tanto mismo valor de ruido.
     *
     * Las coordenadas siguen siendo locales a la region y nunca absolutas: es la estrategia de
     * floating origin de la Seccion 4, y es lo que mantiene el ruido estable a millones de bloques
     * del origen.
     */
    public Result compute() {
        int cells = this.lod.cellsPerSide(RegionKey.REGION_SIZE);
        int corners = cells + 1;
        float[] density = new float[corners * corners];
        DensityField field = new DensityField(this.noise, this.layer, this.coverageScale);

        double originX = this.key.originX();
        double originZ = this.key.originZ();
        double cellSize = this.lod.cellSize();

        for (int cz = 0; cz < corners; cz++) {
            double z = originZ + cz * cellSize;
            for (int cx = 0; cx < corners; cx++) {
                double x = originX + cx * cellSize;
                density[cz * corners + cx] = (float) field.densityAt(x, z);
            }
        }

        // Una celda cuenta si alguna de sus cuatro esquinas tiene densidad: si las cuatro estan en
        // cero, no hay nube en ningun punto de su interior y no se emite nada.
        int nonEmpty = 0;
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                if (density[cz * corners + cx] > 0.0F
                        || density[cz * corners + cx + 1] > 0.0F
                        || density[(cz + 1) * corners + cx] > 0.0F
                        || density[(cz + 1) * corners + cx + 1] > 0.0F) {
                    nonEmpty++;
                }
            }
        }
        return new Result(this, density, cells, nonEmpty);
    }

    /**
     * Resultado listo para que el hilo de render arme la malla.
     *
     * @param density      densidades en las esquinas, de lado {@code cellsPerSide + 1}
     * @param cellsPerSide celdas por lado de la region, no esquinas
     */
    public record Result(DensityJob job, float[] density, int cellsPerSide, int nonEmptyCells) {

        /** Esquinas por lado: una mas que celdas. */
        public int cornersPerSide() {
            return this.cellsPerSide + 1;
        }

        public boolean isEmpty() {
            return this.nonEmptyCells == 0;
        }

        /** Cota superior de cuadruples, para consultar el presupuesto antes de construir. */
        public int estimatedQuads() {
            return this.nonEmptyCells * this.job.lod().slices();
        }
    }
}
