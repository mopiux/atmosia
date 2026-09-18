package dev.mopiux.atmosia;

import net.minecraftforge.fml.common.Mod;

/**
 * Punto de entrada del mod.
 *
 * En esta etapa Atmosia no renderiza nada: solo registra el harness de benchmark, que existe
 * para medir las nubes vanilla y establecer la línea base contra la cual se ratifica el criterio
 * de aceptación (Sección 16.1 del documento de diseño).
 */
@Mod(Atmosia.MOD_ID)
public class Atmosia {
    public static final String MOD_ID = "atmosia";

    public Atmosia() {
        // Nada que registrar acá: el harness se engancha con @Mod.EventBusSubscriber(Dist.CLIENT),
        // que además garantiza que no se cargue en un servidor dedicado. Registrarlo también a
        // mano haría que cada evento llegara dos veces y que cada frame se midiera duplicado.
    }
}
