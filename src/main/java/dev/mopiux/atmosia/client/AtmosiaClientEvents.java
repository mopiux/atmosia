package dev.mopiux.atmosia.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Enganches de render y de tick.
 *
 * Las nubes se dibujan después de los bloques translúcidos y antes del clima: ya están ordenadas
 * respecto del terreno, y la lluvia y la nieve siguen quedando por delante, que es donde el
 * jugador espera verlas.
 *
 * VERIFICAR: que esta etapa sea la correcta en 1.20.1 y cómo se comporta en modo de gráficos
 * fabuloso. Si las nubes tapan la lluvia o desaparecen detrás del agua, este es el punto a mover.
 */
@Mod.EventBusSubscriber(Dist.CLIENT)
public final class AtmosiaClientEvents {

    private AtmosiaClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        CloudRenderer renderer = AtmosiaClient.renderer();
        if (renderer == null || !AtmosiaClient.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Respetar el ajuste del juego: si el jugador ya tenía las nubes apagadas, no se dibuja.
        // El supresor guarda el valor original, así que esto distingue "el jugador no quiere nubes"
        // de "las apagamos nosotros para dibujar las nuestras".
        if (VanillaCloudSuppressor.playerWantsNoClouds()) {
            return;
        }

        renderer.render(
                event.getPoseStack(),
                event.getProjectionMatrix(),
                event.getCamera(),
                mc.level,
                event.getPartialTick(),
                event.getFrustum());
    }

    /** Revisa una vez por tick si corresponde estar activo: cambios de mundo, de config, de mods. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AtmosiaClient.refresh(Minecraft.getInstance().level);
    }
}
