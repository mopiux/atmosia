package dev.mopiux.atmosia.client;

import javax.annotation.Nullable;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suprime las nubes vanilla sin mixin (Sección 13.1 del documento de diseño).
 *
 * El riesgo principal del proyecto no es de rendimiento sino de compatibilidad: cualquier hook
 * sobre LevelRenderer choca con shader packs y mods de optimización. Esta vía no toca LevelRenderer
 * ni comparte punto de inyección con nadie: apaga el ajuste de nubes del propio juego mientras
 * Atmosia dibuja, y lo devuelve como estaba al desactivarse.
 *
 * <h2>Por qué no se usa la ruta de efectos de dimensión</h2>
 *
 * La primera versión intentaba sustituir los efectos del Overworld por unos que declaran una altura
 * de nubes inválida, que es como el Nether y el End no dibujan nubes. En la primera prueba real no
 * funcionó: las nubes vanilla siguieron dibujándose junto a las de Atmosia, con el ajuste del juego
 * todavía en "fancy".
 *
 * La explicación más probable es que ClientLevel resuelve su DimensionSpecialEffects una sola vez,
 * al construirse, y se queda con esa instancia. Sustituir la entrada del mapa después de que el
 * mundo ya existe no cambia nada, y Atmosia se entera del mundo justo después de que carga. Podría
 * funcionar sustituyendo la entrada antes de que se cree cualquier nivel, pero eso obliga a pisar
 * un recurso global compartido con otros mods desde el arranque — exactamente la clase de conflicto
 * que este proyecto decidió evitar.
 *
 * El ajuste de nubes es más tosco porque el jugador lo ve cambiado en el menú, y a cambio es
 * imposible que entre en conflicto con otro mod.
 */
public final class VanillaCloudSuppressor {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    @Nullable
    private static CloudStatus savedStatus;
    private static boolean installed;

    private VanillaCloudSuppressor() {
    }

    public static boolean isActive() {
        return installed;
    }

    public static String strategy() {
        return installed ? "clouds-option-off" : "none";
    }

    /**
     * Si el jugador tenía las nubes apagadas antes de que Atmosia tocara nada.
     *
     * Es un ajuste que el jugador ya conoce y que significa "no quiero nubes". Atmosia lo respeta y
     * no dibuja: reemplazar las nubes vanilla no incluye el derecho a ignorar esa decisión.
     */
    public static boolean playerWantsNoClouds() {
        Minecraft mc = Minecraft.getInstance();
        CloudStatus effective = installed ? savedStatus : mc.options.getCloudsType();
        return effective == CloudStatus.OFF;
    }

    /** Apaga las nubes vanilla. Devuelve false si el jugador ya las tenía apagadas. */
    public static boolean install() {
        if (installed) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        CloudStatus current = mc.options.getCloudsType();
        if (current == CloudStatus.OFF) {
            LOGGER.info("El jugador tiene las nubes en OFF. Atmosia respeta el ajuste y no dibuja.");
            return false;
        }
        savedStatus = current;
        mc.options.cloudStatus().set(CloudStatus.OFF);
        mc.options.save();
        installed = true;
        LOGGER.info("Nubes vanilla suprimidas (ajuste del juego: {} -> OFF).", current);
        return true;
    }

    /** Devuelve el ajuste del jugador como estaba. */
    public static void uninstall() {
        if (!installed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.options.cloudStatus().set(savedStatus != null ? savedStatus : CloudStatus.FANCY);
        mc.options.save();
        LOGGER.info("Nubes vanilla restauradas ({}).", savedStatus);
        savedStatus = null;
        installed = false;
    }
}
