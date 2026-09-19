package dev.mopiux.atmosia.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/**
 * Tipo de render de las nubes (Seccion 11.1 del documento de diseno).
 *
 * Las decisiones de transparencia, en un solo lugar:
 *
 * - Mezcla translucida normal. Las nubes escriben color pero NO profundidad: son volumen, no
 *   superficie, y escribir profundidad haria que los slices se ocultaran entre si.
 * - Si leen profundidad, asi que quedan correctamente tapadas por montanas y estructuras.
 * - Sin cull de caras: los slices son planos horizontales y se ven desde arriba y desde abajo.
 * - Sin textura. El color viene por vertice, con el sombreado ya horneado, y el tinte del momento
 *   del dia se aplica como uniforme al dibujar. Eso evita tener que registrar shaders propios y
 *   mantiene el mod en la ruta de renderizado estandar de Forge, como pide la Seccion 9.3.
 *
 * SIN VERIFICAR contra el juego real.
 */
public final class AtmosiaRenderType extends RenderType {

    /** Nunca se instancia: extiende RenderType solo para alcanzar los estados protegidos. */
    private AtmosiaRenderType() {
        super(null, null, null, 0, false, false, null, null);
        throw new AssertionError();
    }

    public static final RenderType CLOUDS = RenderType.create(
            "atmosia_clouds",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));
}
