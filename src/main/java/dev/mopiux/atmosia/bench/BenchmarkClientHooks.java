package dev.mopiux.atmosia.bench;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Enganches de cliente del harness.
 *
 * El frame se mide entre el inicio de un render tick y el del siguiente, que es el intervalo que
 * el jugador percibe como framerate. Medir dentro del render del mundo dejaria afuera la GUI y la
 * presentacion, y daria numeros mejores que la realidad.
 */
@Mod.EventBusSubscriber(Dist.CLIENT)
public final class BenchmarkClientHooks {

    private BenchmarkClientHooks() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        BenchmarkRunner runner = BenchmarkRunner.get();
        if (!runner.isRunning()) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            runner.onFrameStart();
        } else {
            runner.onFrameEnd();
        }
    }
}
