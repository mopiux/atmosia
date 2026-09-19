package dev.mopiux.atmosia.core;

/**
 * Niveles de detalle (Seccion 7 del documento de diseno).
 *
 * En esta arquitectura el LOD no es "menos vertices de la misma forma": es menos slices verticales
 * y celdas mas grandes. Los slices son lo que da la profundidad volumetrica y lo que cuesta
 * relleno, asi que reducirlos a distancia es exactamente donde esta el ahorro.
 *
 * @param slices   cortes horizontales por capa
 * @param cellSize lado de celda en bloques
 * @param layers   cuantas capas se dibujan en este nivel, de la mas baja hacia arriba
 */
public enum LodLevel {

    HIGH(8, 16, 3),
    MEDIUM(4, 16, 3),
    LOW(2, 32, 2);

    // La celda no crece tanto como el ahorro tentaria: una celda de 128 bloques se ve como una
    // sabana rectangular en el cielo, no como una nube, por lejos que este. El ahorro a distancia
    // sale de los slices, que es donde esta el costo de relleno.
    //
    // Hubo un cuarto nivel, MINIMAL, con un corte y celdas de 64 bloques. Se quito porque era
    // inalcanzable -el tramo de LOW llegaba hasta el borde del domo y no dejaba lugar para otro-
    // y porque, de haberse alcanzado, sus celdas de 64 bloques reintroducian exactamente el
    // defecto que la 0.0.1 corrigio. Codigo muerto que ademas era codigo danino.
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

    /** Celdas por lado de una region de {@code regionSize} bloques. */
    public int cellsPerSide(int regionSize) {
        return Math.max(1, regionSize / this.cellSize);
    }

    /** Cota superior de cuadruples por region y capa, para el presupuesto de vertices. */
    public int maxQuadsPerRegionLayer(int regionSize) {
        int cells = this.cellsPerSide(regionSize);
        return cells * cells * this.slices;
    }

    /** Nivel inmediatamente mas detallado, o el mismo si ya es el maximo. */
    public LodLevel finer() {
        return this.ordinal() == 0 ? this : values()[this.ordinal() - 1];
    }
}
