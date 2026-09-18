package dev.mopiux.atmosia.client;

import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.bench.CloudMetricsProvider;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estado del cliente: decide si Atmosia debe estar activo y sostiene el renderer.
 *
 * Acá vive la política de convivencia de la Sección 13.1: si hay un shader pack activo, Atmosia
 * cede el cielo en vez de pelearlo. Competir con un pack por el render del cielo produce errores
 * que nadie puede reproducir ni diagnosticar, y el pack ya dibuja sus propias nubes.
 */
public final class AtmosiaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    /** Mods que también reemplazan las nubes vanilla: con ellos no se puede coexistir. */
    private static final String[] CONFLICTING_CLOUD_MODS = { "simpleclouds" };

    /** Mods de shaders que gobiernan el cielo cuando tienen un pack cargado. */
    private static final String[] SHADER_MODS = { "oculus", "iris" };

    @Nullable
    private static CloudRenderer renderer;
    private static boolean standDown;
    private static boolean reported;

    private AtmosiaClient() {
    }

    @Nullable
    public static CloudRenderer renderer() {
        return renderer;
    }

    public static boolean isActive() {
        return renderer != null && !standDown;
    }

    /** Evalúa si corresponde estar activo y arma o destruye el renderer en consecuencia. */
    public static void refresh(@Nullable ClientLevel level) {
        if (level == null || !AtmosiaConfig.CLIENT.enabled.get() || shouldStandDown()) {
            shutdown();
            return;
        }
        if (renderer == null) {
            long seed = resolveSeed(level);
            renderer = new CloudRenderer(seed);
            CloudMetricsProvider.Registry.set(renderer);
            VanillaCloudSuppressor.install();
            LOGGER.info("Atmosia activo. Seed {}, supresión de vanilla: {}",
                    seed, VanillaCloudSuppressor.strategy());
        }
    }

    public static void shutdown() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
            CloudMetricsProvider.Registry.set(null);
            VanillaCloudSuppressor.uninstall();
            LOGGER.info("Atmosia desactivado.");
        }
    }

    /**
     * Si hay que ceder el cielo a otro mod.
     *
     * VERIFICAR: detectar un shader pack *cargado* requiere consultar la API de Iris, que no es
     * estable entre versiones. Por ahora basta con que el mod esté presente, que es conservador:
     * prefiere no dibujar a dibujar mal.
     */
    private static boolean shouldStandDown() {
        standDown = false;
        String reason = null;

        for (String mod : CONFLICTING_CLOUD_MODS) {
            if (ModList.get() != null && ModList.get().isLoaded(mod)) {
                standDown = true;
                reason = "el mod " + mod + " también reemplaza las nubes vanilla";
                break;
            }
        }

        if (!standDown && AtmosiaConfig.CLIENT.standDownForShaderPacks.get()) {
            for (String mod : SHADER_MODS) {
                if (ModList.get() != null && ModList.get().isLoaded(mod)) {
                    standDown = true;
                    reason = "hay un mod de shaders instalado (" + mod + ") que gobierna el cielo";
                    break;
                }
            }
        }

        if (standDown && !reported) {
            reported = true;
            LOGGER.info("Atmosia se desactiva: {}. Las nubes vanilla quedan como están.", reason);
        }
        return standDown;
    }

    /**
     * Seed de las nubes (Sección 7 de la Fase 0).
     *
     * Atmosia es de cliente y en un servidor remoto el cliente no conoce la seed del mundo, así que
     * se deriva de un dato estable que sí tiene: la dirección del servidor, o el nombre de la
     * dimensión en un mundo local. La consecuencia está asumida y documentada: dos jugadores del
     * mismo servidor no ven necesariamente las mismas nubes. Quien quiera igualarlas puede fijar la
     * misma seed a mano en la configuración.
     */
    private static long resolveSeed(ClientLevel level) {
        long override = AtmosiaConfig.CLIENT.seedOverride.get();
        if (override != 0L) {
            return override;
        }

        StringBuilder source = new StringBuilder();
        ServerData server = Minecraft.getInstance().getCurrentServer();
        source.append(server != null ? server.ip : "local");
        source.append('/').append(level.dimension().location());
        return source.toString().hashCode() * 0x9E3779B97F4A7C15L;
    }
}
