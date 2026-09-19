package dev.mopiux.atmosia.client.sprite;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/**
 * Estado de render de los sprites.
 *
 * Mismas decisiones que la tecnica de planos -translucido, sin escribir profundidad, sin cull- mas
 * la textura del bulto, que es lo que le da el borde suave.
 */
public final class SpriteRenderType extends RenderType {

    private SpriteRenderType() {
        super(null, null, null, 0, false, false, null, null);
        throw new AssertionError();
    }

    public static final RenderType CLOUDS = RenderType.create(
            "atmosia_puffs",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(new TextureStateShard(SpriteCloudRenderer.PUFF_TEXTURE, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));
}
