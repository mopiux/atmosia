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
    LOW(2, 16, 2);

    // El lado de celda es el MISMO en los tres niveles, a proposito.
    //
    // Cambiarlo entre niveles cambia la silueta de la nube -una grilla mas gruesa activa o no
    // activa celdas donde la fina hace lo contrario- y eso es una discontinuidad de forma en el
    // borde entre dos regiones vecinas, que ninguna correccion de color puede tapar. Con el lado
    // igual en todos los niveles, lo unico que cambia el nivel de detalle es cuantos cortes hay, y
    // eso si se puede igualar exactamente (ver DensityField.levelMatch).
    //
    // El ahorro a distancia sale de los cortes, que es donde esta el costo de relleno: pasar de
    // ocho a dos es cuatro veces menos pixeles pintados.
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
