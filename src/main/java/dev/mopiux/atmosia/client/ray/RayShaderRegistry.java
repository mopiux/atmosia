package dev.mopiux.atmosia.client.ray;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.mopiux.atmosia.Atmosia;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registra el shader del ray marching.
 *
 * Si el registro falla, {@link RayCloudRenderer} queda sin shader y el mod usa otra tecnica: un
 * shader que no carga no puede dejar el cielo roto.
 */
@Mod.EventBusSubscriber(modid = Atmosia.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RayShaderRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("atmosia");

    private RayShaderRegistry() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            new ResourceLocation(Atmosia.MOD_ID, "atmosia_clouds"),
                            DefaultVertexFormat.POSITION_TEX),
                    RayCloudRenderer::setShader);
            LOGGER.info("Shader de ray marching registrado.");
        } catch (Exception e) {
            LOGGER.error("No se pudo registrar el shader de ray marching. "
                    + "La tecnica de ray marching va a quedar deshabilitada.", e);
            RayCloudRenderer.setShader(null);
        }
    }
}
