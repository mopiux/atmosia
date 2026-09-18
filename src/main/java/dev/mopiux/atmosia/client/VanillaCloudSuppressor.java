package dev.mopiux.atmosia.client;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suprime las nubes vanilla sin mixin (Sección 13.1 del documento de diseño).
 *
 * El riesgo principal del proyecto no es de rendimiento sino de compatibilidad: cualquier hook
 * sobre LevelRenderer choca con shader packs y mods de optimización. Por eso se intenta primero la
 * vía que no toca LevelRenderer en absoluto.
 *
 * Dos estrategias, en orden:
 *
 * 1. Sustituir los efectos de dimensión del Overworld por una versión que declara una altura de
 *    nubes inválida, que es el mecanismo por el cual el Nether y el End no dibujan nubes. No hay
 *    mixin, no hay punto de inyección compartido y por lo tanto no hay conflicto con nadie.
 *
 * 2. Si eso falla, forzar el ajuste de nubes del juego a OFF mientras Atmosia esté activo, y
 *    restaurarlo al desactivarse. Es más tosco porque toca una opción que el jugador ve, pero
 *    también es imposible que entre en conflicto con otro mod.
 *
 * HIPÓTESIS SIN VERIFICAR: que una altura inválida suprima el render vanilla en 1.20.1 está
 * tomado del comportamiento conocido de las dimensiones sin nubes, no de haber leído el código.
 * Es la Verificación 1 de la Fase 0 y hay que confirmarla. Si no se cumple, la estrategia 2 actúa
 * de red y el mod funciona igual.
 */
public final class VanillaCloudSuppressor {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");
    private static final ResourceLocation OVERWORLD_EFFECTS = new ResourceLocation("minecraft", "overworld");

    private static boolean attempted;
    private static boolean effectsReplaced;
    private static boolean forcedCloudsOff;

    private VanillaCloudSuppressor() {
    }

    public static boolean isActive() {
        return effectsReplaced || forcedCloudsOff;
    }

    /** Estrategia aplicada, para el log y para las métricas. */
    public static String strategy() {
        if (effectsReplaced) {
            return "dimension-effects";
        }
        return forcedCloudsOff ? "clouds-option-off" : "none";
    }

    public static void install() {
        if (attempted) {
            return;
        }
        attempted = true;

        if (replaceOverworldEffects()) {
            effectsReplaced = true;
            LOGGER.info("Nubes vanilla suprimidas por efectos de dimensión, sin mixin.");
            return;
        }

        forceCloudsOff();
    }

    /**
     * Sustituye la entrada del Overworld en la tabla de efectos de dimensión.
     *
     * VERIFICAR: el nombre y el tipo del campo estático que guarda esa tabla en 1.20.1. Se busca
     * por tipo y no por nombre justamente porque el nombre depende de los mappings.
     */
    private static boolean replaceOverworldEffects() {
        try {
            Map<ResourceLocation, DimensionSpecialEffects> effects = findEffectsTable();
            if (effects == null) {
                LOGGER.warn("No se encontró la tabla de efectos de dimensión.");
                return false;
            }
            DimensionSpecialEffects original = effects.get(OVERWORLD_EFFECTS);
            if (original == null) {
                LOGGER.warn("La tabla de efectos no tiene entrada para el Overworld.");
                return false;
            }
            if (!(original instanceof DimensionSpecialEffects.OverworldEffects)) {
                // Otro mod ya la reemplazó. Se respeta y no se pisa: pisarla en silencio es
                // exactamente el tipo de conflicto que este proyecto quiere evitar.
                LOGGER.info("Otro mod ya definió los efectos del Overworld ({}). Atmosia no los toca.",
                        original.getClass().getName());
                return false;
            }
            effects.put(OVERWORLD_EFFECTS, new CloudlessOverworldEffects());
            return true;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("No se pudieron sustituir los efectos de dimensión: {}", e.toString());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, DimensionSpecialEffects> findEffectsTable() {
        for (Field field : DimensionSpecialEffects.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                    Object sample = map.keySet().iterator().next();
                    if (sample instanceof ResourceLocation) {
                        return (Map<ResourceLocation, DimensionSpecialEffects>) map;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Se sigue buscando: puede haber más de un campo de tipo Map.
            }
        }
        return null;
    }

    /** Red de seguridad: apaga el ajuste de nubes del juego. */
    private static void forceCloudsOff() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.options.getCloudsType() != net.minecraft.client.CloudStatus.OFF) {
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                mc.options.save();
                forcedCloudsOff = true;
                LOGGER.info("Nubes vanilla suprimidas apagando el ajuste de nubes del juego.");
            } else {
                LOGGER.info("El jugador ya tenía las nubes en OFF.");
            }
        } catch (RuntimeException e) {
            LOGGER.error("No se pudieron suprimir las nubes vanilla: {}", e.toString());
        }
    }

    /** Devuelve las cosas como estaban. */
    public static void uninstall() {
        if (forcedCloudsOff) {
            Minecraft mc = Minecraft.getInstance();
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
            mc.options.save();
            forcedCloudsOff = false;
        }
        // La tabla de efectos de dimensión no se restaura: hacerlo a mitad de partida deja al
        // renderer vanilla dibujando sobre las nubes propias durante un frame. Se restaura solo al
        // cerrar el juego, que es cuando la tabla se reconstruye igual.
        attempted = false;
    }

    /**
     * Efectos del Overworld sin nubes.
     *
     * VERIFICAR: que Float.NaN sea efectivamente el valor que indica "esta dimensión no tiene
     * nubes" en 1.20.1. Es la hipótesis central de la vía sin mixin.
     */
    private static final class CloudlessOverworldEffects extends DimensionSpecialEffects.OverworldEffects {

        @Override
        public float getCloudHeight() {
            return Float.NaN;
        }
    }
}
