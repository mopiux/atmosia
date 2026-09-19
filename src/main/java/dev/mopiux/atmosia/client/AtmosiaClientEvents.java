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
 * Las nubes se dibujan despues de los bloques translucidos y antes del clima: ya estan ordenadas
 * respecto del terreno, y la lluvia y la nieve siguen quedando por delante, que es donde el
 * jugador espera verlas.
 *
 * VERIFICAR: que esta etapa sea la correcta en 1.20.1 y como se comporta en modo de graficos
 * fabuloso. Si las nubes tapan la lluvia o desaparecen detras del agua, este es el punto a mover.
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
        if (!AtmosiaClient.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        // Cada tecnica dibuja lo suyo. Solo una esta viva a la vez.
        CloudRenderer planos = AtmosiaClient.renderer();
        if (planos != null) {
            planos.render(event.getPoseStack(), event.getProjectionMatrix(), event.getCamera(),
                    mc.level, event.getPartialTick(), event.getFrustum());
            return;
        }
        var puffs = AtmosiaClient.sprites();
        if (puffs != null) {
            puffs.render(event.getPoseStack(), event.getProjectionMatrix(), event.getCamera(),
                    mc.level, event.getPartialTick());
            return;
        }
        var rayos = AtmosiaClient.ray();
        if (rayos != null) {
            rayos.render(event.getPoseStack(), event.getProjectionMatrix(), event.getCamera(),
                    mc.level, event.getPartialTick());
        }
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
