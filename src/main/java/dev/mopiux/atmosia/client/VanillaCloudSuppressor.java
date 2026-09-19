package dev.mopiux.atmosia.client;

import javax.annotation.Nullable;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suprime las nubes vanilla sin mixin (Seccion 13.1 del documento de diseno).
 *
 * El riesgo principal del proyecto no es de rendimiento sino de compatibilidad: cualquier hook
 * sobre LevelRenderer choca con shader packs y mods de optimizacion. Esta via no toca LevelRenderer
 * ni comparte punto de inyeccion con nadie: apaga el ajuste de nubes del propio juego mientras
 * Atmosia dibuja, y lo devuelve como estaba al desactivarse.
 *
 * <h2>Por que no se usa la ruta de efectos de dimension</h2>
 *
 * La primera version intentaba sustituir los efectos del Overworld por unos que declaran una altura
 * de nubes invalida, que es como el Nether y el End no dibujan nubes. No funciono: ClientLevel
 * resuelve su DimensionSpecialEffects una sola vez, al construirse, y se queda con esa instancia.
 * Sustituir la entrada del mapa despues de que el mundo ya existe no cambia nada, y Atmosia se
 * entera del mundo justo despues de que carga.
 *
 * <h2>Por que se reaplica cada tick</h2>
 *
 * La segunda version apagaba el ajuste una sola vez, al activarse, y el jugador siguio viendo nubes
 * vanilla. Aplicar una vez y confiar deja demasiadas formas de perder el ajuste: el menu de opciones
 * lo reescribe al cerrarse, un archivo de opciones que se recarga lo devuelve a su valor guardado,
 * y cualquier otro mod que lo toque gana por ser el ultimo. Ninguna de esas se puede prevenir desde
 * aca, pero todas se pueden corregir: {@link #enforce()} corre una vez por tick, comprueba el valor
 * real y lo vuelve a poner si alguien lo movio. Cuesta una comparacion de enums por tick.
 */
public final class VanillaCloudSuppressor {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    /** El ajuste que tenia el jugador antes de que Atmosia lo tocara, para devolverselo. */
    @Nullable
    private static CloudStatus savedStatus;

    private static boolean installed;

    /** Para no llenar el log si algo pelea el ajuste todos los ticks. */
    private static int reapplyCount;

    private VanillaCloudSuppressor() {
    }

    public static boolean isActive() {
        return installed;
    }

    public static String strategy() {
        return installed ? "clouds-option-off" : "none";
    }

    /** El ajuste de nubes que el juego tiene ahora mismo, para mostrarlo en el menu. */
    public static CloudStatus currentGameSetting() {
        return Minecraft.getInstance().options.cloudStatus().get();
    }

    /** El ajuste que el jugador tenia antes de que Atmosia lo tocara. */
    @Nullable
    public static CloudStatus savedSetting() {
        return savedStatus;
    }

    /** Cuantas veces hubo que reaplicar el ajuste porque alguien lo movio. Diagnostico. */
    public static int reapplyCount() {
        return reapplyCount;
    }

    /**
     * Apaga las nubes vanilla y recuerda el valor original.
     *
     * A diferencia de la version anterior, no le da a un ajuste del juego el poder de desactivar el
     * mod: el jugador tiene ahora un interruptor propio en el menu de Atmosia, con tres estados, y
     * ese es el que manda. Si tenia las nubes en OFF, se guarda ese OFF y se le devuelve intacto al
     * desactivar el mod; mientras tanto, Atmosia dibuja.
     */
    public static void install() {
        if (installed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        savedStatus = mc.options.cloudStatus().get();
        installed = true;
        reapplyCount = 0;
        apply(mc);
        LOGGER.info("Nubes vanilla suprimidas (ajuste del juego: {} -> OFF).", savedStatus);
    }

    /**
     * Vuelve a poner el ajuste en OFF si alguien lo movio. Una vez por tick.
     *
     * @return true si hubo que corregirlo en este tick
     */
    public static boolean enforce() {
        if (!installed) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.cloudStatus().get() == CloudStatus.OFF) {
            return false;
        }
        apply(mc);
        reapplyCount++;
        if (reapplyCount == 1 || reapplyCount % 200 == 0) {
            LOGGER.info("El ajuste de nubes del juego volvio a encenderse y se apago de nuevo "
                    + "({} veces). Atmosia dibuja las suyas.", reapplyCount);
        }
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
        reapplyCount = 0;
    }

    private static void apply(Minecraft mc) {
        mc.options.cloudStatus().set(CloudStatus.OFF);
        // Guardar en disco ademas de en memoria: sin esto, el menu de opciones puede recargar el
        // archivo y devolver el valor viejo, que es una de las formas de perder el ajuste.
        mc.options.save();
    }
}
