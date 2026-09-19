package dev.mopiux.atmosia.client;

import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.bench.CloudMetricsProvider;
import dev.mopiux.atmosia.core.CloudMode;
import dev.mopiux.atmosia.core.RenderTechnique;
import dev.mopiux.atmosia.client.ray.RayCloudRenderer;
import dev.mopiux.atmosia.client.sprite.SpriteCloudRenderer;
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
 * Aca vive la politica de convivencia de la Seccion 13.1: si hay un shader pack activo, Atmosia
 * cede el cielo en vez de pelearlo. Competir con un pack por el render del cielo produce errores
 * que nadie puede reproducir ni diagnosticar, y el pack ya dibuja sus propias nubes.
 */
public final class AtmosiaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    /** Mods que tambien reemplazan las nubes vanilla: con ellos no se puede coexistir. */
    private static final String[] CONFLICTING_CLOUD_MODS = { "simpleclouds" };

    /** Mods de shaders que gobiernan el cielo cuando tienen un pack cargado. */
    private static final String[] SHADER_MODS = { "oculus", "iris" };

    @Nullable
    private static CloudRenderer renderer;

    @Nullable
    private static SpriteCloudRenderer sprites;

    @Nullable
    private static RayCloudRenderer ray;

    /** La tecnica con la que esta armado lo que hay vivo ahora mismo. */
    @Nullable
    private static RenderTechnique activeTechnique;
    private static boolean standDown;
    private static boolean reported;

    @Nullable
    private static String standDownReason;

    private AtmosiaClient() {
    }

    @Nullable
    public static CloudRenderer renderer() {
        return renderer;
    }

    @Nullable
    public static SpriteCloudRenderer sprites() {
        return sprites;
    }

    @Nullable
    public static RayCloudRenderer ray() {
        return ray;
    }

    /**
     * La tecnica efectiva, que puede no ser la pedida.
     *
     * Si se pidio ray marching y el shader no cargo, se usa sprites: un shader que no anda no puede
     * dejar el cielo vacio sin explicacion.
     */
    public static RenderTechnique effectiveTechnique() {
        RenderTechnique pedida = AtmosiaConfig.CLIENT.renderTechnique.get();
        if (pedida == RenderTechnique.RAYMARCH && !RayCloudRenderer.shaderReady()) {
            return RenderTechnique.SPRITES;
        }
        return pedida;
    }

    public static boolean isActive() {
        return !standDown && (renderer != null || sprites != null || ray != null);
    }

    /**
     * Evalua que corresponde hacer y ajusta el estado en consecuencia. Una vez por tick.
     *
     * Las dos decisiones -si se suprimen las nubes vanilla y si Atmosia dibuja las suyas- salen del
     * mismo modo pero son independientes: el modo NINGUNA suprime sin dibujar, y es el que convierte
     * "quedaron nubes vanilla?" en una pregunta que el jugador puede responder mirando el cielo.
     */
    public static void refresh(@Nullable ClientLevel level) {
        if (level == null) {
            shutdown();
            VanillaCloudSuppressor.uninstall();
            return;
        }

        CloudMode mode = AtmosiaConfig.CLIENT.cloudMode.get();
        if (shouldStandDown()) {
            // Otro mod gobierna el cielo: se le devuelve entero, nubes vanilla incluidas.
            shutdown();
            VanillaCloudSuppressor.uninstall();
            return;
        }

        if (mode.suppressesVanilla()) {
            VanillaCloudSuppressor.install();
            VanillaCloudSuppressor.enforce();
        } else {
            VanillaCloudSuppressor.uninstall();
        }

        if (!mode.drawsAtmosia()) {
            shutdown();
            return;
        }

        RenderTechnique tecnica = effectiveTechnique();
        if (activeTechnique != tecnica) {
            shutdown();
            activeTechnique = tecnica;
        }

        if (renderer == null && sprites == null && ray == null) {
            long seed = resolveSeed(level);
            switch (tecnica) {
                case SPRITES -> {
                    sprites = new SpriteCloudRenderer(seed);
                    CloudMetricsProvider.Registry.set(sprites);
                }
                case RAYMARCH -> {
                    ray = new RayCloudRenderer(seed);
                    CloudMetricsProvider.Registry.set(ray);
                }
                default -> {
                    renderer = new CloudRenderer(seed);
                    CloudMetricsProvider.Registry.set(renderer);
                }
            }
            LOGGER.info("Atmosia activo. Tecnica {}, seed {}, supresion de vanilla: {}",
                    tecnica.displayName(), seed, VanillaCloudSuppressor.strategy());
        }
    }

    /** Tira la geometria y la vuelve a construir. Para cambios que la cache no puede detectar. */
    public static void invalidate() {
        if (renderer != null) {
            long seed = renderer.seed();
            renderer.close();
            renderer = new CloudRenderer(seed);
            CloudMetricsProvider.Registry.set(renderer);
        }
        if (sprites != null) {
            long seed = sprites.seed();
            sprites.close();
            sprites = new SpriteCloudRenderer(seed);
            CloudMetricsProvider.Registry.set(sprites);
        }
    }

    /** Por que Atmosia cedio el cielo, o null si no cedio. */
    @Nullable
    public static String standDownReason() {
        return standDown ? standDownReason : null;
    }

    /** Suelta el renderer. No toca el ajuste de nubes del juego: de eso decide {@link #refresh}. */
    public static void shutdown() {
        boolean habia = renderer != null || sprites != null || ray != null;
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (sprites != null) {
            sprites.close();
            sprites = null;
        }
        if (ray != null) {
            ray.close();
            ray = null;
        }
        if (habia) {
            CloudMetricsProvider.Registry.set(null);
            activeTechnique = null;
            LOGGER.info("Atmosia desactivado.");
        }
    }

    /**
     * Si hay que ceder el cielo a otro mod.
     *
     * VERIFICAR: detectar un shader pack *cargado* requiere consultar la API de Iris, que no es
     * estable entre versiones. Por ahora basta con que el mod este presente, que es conservador:
     * prefiere no dibujar a dibujar mal.
     */
    private static boolean shouldStandDown() {
        standDown = false;
        String reason = null;

        for (String mod : CONFLICTING_CLOUD_MODS) {
            if (ModList.get() != null && ModList.get().isLoaded(mod)) {
                standDown = true;
                reason = "el mod " + mod + " tambien reemplaza las nubes vanilla";
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

        standDownReason = reason;
        if (standDown && !reported) {
            reported = true;
            LOGGER.info("Atmosia se desactiva: {}. Las nubes vanilla quedan como estan.", reason);
        }
        return standDown;
    }

    /**
     * Seed de las nubes (Seccion 7 de la Fase 0).
     *
     * Atmosia es de cliente y en un servidor remoto el cliente no conoce la seed del mundo, asi que
     * se deriva de un dato estable que si tiene: la direccion del servidor, o el nombre de la
     * dimension en un mundo local. La consecuencia esta asumida y documentada: dos jugadores del
     * mismo servidor no ven necesariamente las mismas nubes. Quien quiera igualarlas puede fijar la
     * misma seed a mano en la configuracion.
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
