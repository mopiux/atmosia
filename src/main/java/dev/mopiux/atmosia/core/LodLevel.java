package dev.mopiux.atmosia.core;

/**
 * Niveles de detalle (Sección 7 del documento de diseño).
 *
 * En esta arquitectura el LOD no es "menos vértices de la misma forma": es menos slices verticales
 * y celdas más grandes. Los slices son lo que da la profundidad volumétrica y lo que cuesta
 * relleno, así que reducirlos a distancia es exactamente donde está el ahorro.
 *
 * @param slices   cortes horizontales por capa
 * @param cellSize lado de celda en bloques
 * @param layers   cuántas capas se dibujan en este nivel, de la más baja hacia arriba
 */
public enum LodLevel {

    HIGH(8, 16, 3),
    MEDIUM(4, 16, 3),
    LOW(2, 32, 2),
    MINIMAL(1, 64, 1);

    // La celda no crece tanto como el ahorro tentaría: una celda de 128 bloques se ve como una
    // sábana rectangular en el cielo, no como una nube, por lejos que esté. El ahorro a distancia
    // sale de los slices, que es donde está el costo de relleno.
    private final int slices;
    private final int cellSize;
    private final int layers;

    LodLevel(int slices, int cellSize, int layers) {
        this.slices = slices;
        this.cellSize = cellSize;
        this.layers = layers;
    }

    public int slices() {
        return this.slices;
    }

    public int cellSize() {
        return this.cellSize;
    }

    public int layers() {
        return this.layers;
    }

    /** Celdas por lado de una región de {@code regionSize} bloques. */
    public int cellsPerSide(int regionSize) {
        return Math.max(1, regionSize / this.cellSize);
    }

    /** Cota superior de cuádruples por región y capa, para el presupuesto de vértices. */
    public int maxQuadsPerRegionLayer(int regionSize) {
        int cells = this.cellsPerSide(regionSize);
        return cells * cells * this.slices;
    }

    /** Nivel inmediatamente más detallado, o el mismo si ya es el máximo. */
    public LodLevel finer() {
        return this.ordinal() == 0 ? this : values()[this.ordinal() - 1];
    }
}
