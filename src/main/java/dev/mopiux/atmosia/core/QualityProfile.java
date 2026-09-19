package dev.mopiux.atmosia.core;

/**
 * Perfiles graficos: tres combinaciones probadas, mas una manual.
 *
 * El documento de diseno deja todos los parametros de rendimiento configurables uno por uno
 * (Seccion 13.2), que esta bien para medir pero no para jugar: nadie que quiera que le ande el mod
 * sabe que es un presupuesto de cuadruples por frame. Un perfil es una combinacion de esos valores
 * que ya se movieron juntos en la direccion correcta.
 *
 * Los tres perfiles se mueven en los dos ejes que de verdad cuestan, y en ninguno mas:
 *
 * <ul>
 *   <li><b>Cortes por capa</b> (el tope de detalle). Es relleno puro: cada corte es una capa mas de
 *       transparencia sobre los mismos pixeles. Es lo primero que hay que bajar en una placa
 *       modesta, y lo ultimo que se nota mirando el cielo.</li>
 *   <li><b>Alcance del domo</b>. Cuesta regiones, memoria y draw calls, y crece con el cuadrado de
 *       la distancia.</li>
 * </ul>
 *
 * Lo que <em>no</em> cambia entre perfiles es el tamano de celda: una celda grande se lee como un
 * rectangulo en el cielo por lejos que este, y ese es un defecto visual, no un ajuste de calidad.
 */
public enum QualityProfile {

    LOW("Bajo", "Mitad de cortes por capa y domo corto. Para placas modestas.",
            new Settings(1.5D, LodLevel.MEDIUM, 1, 8_000, 128)),

    MEDIUM("Medio", "El equilibrio por defecto.",
            new Settings(3.0D, LodLevel.HIGH, 2, 24_000, 384)),

    HIGH("Alto", "Detalle completo y domo largo. Pide una placa dedicada.",
            new Settings(4.5D, LodLevel.HIGH, 4, 64_000, 768)),

    /** Los valores sueltos del archivo de configuracion, para medir y para quien quiera afinarlos. */
    CUSTOM("Personalizado", "Usa los valores sueltos del archivo de configuracion.", null);

    private final String displayName;
    private final String description;
    private final Settings settings;

    QualityProfile(String displayName, String description, Settings settings) {
        this.displayName = displayName;
        this.description = description;
        this.settings = settings;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    /** Si este perfil deja mandar a los valores sueltos de la configuracion. */
    public boolean usesConfigValues() {
        return this.settings == null;
    }

    /**
     * Los valores efectivos de este perfil.
     *
     * @param fromConfig los valores sueltos del archivo, que solo se usan en {@link #CUSTOM}
     */
    public Settings resolve(Settings fromConfig) {
        return this.settings != null ? this.settings : fromConfig;
    }

    /**
     * Valores de rendimiento ya resueltos.
     *
     * @param distanceMultiplier multiplicador sobre el render distance del jugador
     * @param detailCap          nivel de detalle maximo permitido, aunque la distancia de para mas
     * @param regionsPerFrame    regiones subidas a GPU por frame
     * @param quadsPerFrame      cuadruples construidos por frame
     * @param maxCachedRegions   regiones vivas en cache
     */
    public record Settings(double distanceMultiplier, LodLevel detailCap, int regionsPerFrame,
                           int quadsPerFrame, int maxCachedRegions) {
    }
}
