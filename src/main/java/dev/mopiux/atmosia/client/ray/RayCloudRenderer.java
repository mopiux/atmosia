package dev.mopiux.atmosia.client.ray;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.bench.CloudMetricsProvider;
import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.LodSelector;
import dev.mopiux.atmosia.core.NoiseField;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Nubes por ray marching: un solo dibujado para todo el cielo.
 *
 * <h2>Por que esta tecnica no puede tener costuras</h2>
 *
 * Las otras dos dibujan geometria, y toda geometria hay que partirla en pedazos y mezclarlos en
 * algun orden. Ese orden es lo que produce las rectas en el cielo. Aca no hay geometria: se dibuja
 * un cuadrilatero que cubre la pantalla y por cada pixel se avanza a pasos dentro del volumen
 * acumulando densidad. No hay regiones, no hay cortes, no hay nada que ordenar.
 *
 * <h2>Lo que cuesta</h2>
 *
 * El costo es por pixel y se controla con un solo numero: la cantidad de pasos. Y es el unico
 * camino de los tres que puede representar volumen de verdad -luz que atraviesa la nube, sombra
 * propia, bordes luminosos a contraluz- que es lo que hace falta para nubes de tormenta.
 *
 * <h2>Advertencia</h2>
 *
 * Esta clase y su shader NO SE COMPILARON NI EJECUTARON contra el juego. El shader
 * {@code position_tex} propio se registra por {@code RegisterShadersEvent}. Si algo falla, el mod
 * cae a la tecnica de sprites en vez de romperse.
 */
public final class RayCloudRenderer implements CloudMetricsProvider {

    /** Lado de la textura de ruido. Se repite, asi que no hace falta que sea grande. */
    private static final int NOISE_SIZE = 256;

    @Nullable
    private static ShaderInstance shader;

    @Nullable
    private DynamicTexture noiseTexture;

    @Nullable
    private ResourceLocation noiseId;

    private final long seed;
    private final NoiseField noise;
    private int lastDrawCalls;
    private boolean failed;

    public RayCloudRenderer(long seed) {
        this.seed = seed;
        this.noise = new NoiseField(seed);
    }

    public static void setShader(@Nullable ShaderInstance instance) {
        shader = instance;
    }

    public static boolean shaderReady() {
        return shader != null;
    }

    public long seed() {
        return this.seed;
    }

    /** Si el shader no cargo o algo fallo, quien llama deberia usar otra tecnica. */
    public boolean isUnavailable() {
        return this.failed || shader == null;
    }

    /**
     * Textura de ruido derivada del MISMO campo que usan las otras tecnicas.
     *
     * No es el mismo ruido bit a bit -el hash de 64 bits del nucleo no se puede reproducir en GLSL
     * 150- pero sale de la misma seed y tiene el mismo caracter, asi que las nubes se parecen.
     */
    private void ensureNoise() {
        if (this.noiseTexture != null) {
            return;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, NOISE_SIZE, NOISE_SIZE, false);
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                double v = this.noise.valueNoise(x, y, 0);
                int b = (int) Math.max(0, Math.min(255, Math.round(v * 255.0)));
                image.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (b << 8) | b);
            }
        }
        this.noiseTexture = new DynamicTexture(image);
        this.noiseId = Minecraft.getInstance().getTextureManager()
                .register("atmosia_ray_noise", this.noiseTexture);
    }

    public void render(PoseStack poseStack, Matrix4f projection, Camera camera, ClientLevel level,
                       float partialTick) {
        if (this.isUnavailable()) {
            return;
        }
        this.lastDrawCalls = 0;
        try {
            this.ensureNoise();

            double seconds = (level.getGameTime() + partialTick) / 20.0D;
            double speedScale = AtmosiaConfig.CLIENT.speedScale.get();
            Vec3 cameraPos = camera.getPosition();

            LodSelector selector = LodSelector.forRenderDistance(
                    Minecraft.getInstance().options.renderDistance().get(),
                    AtmosiaConfig.CLIENT.resolvedQuality().distanceMultiplier(),
                    AtmosiaConfig.CLIENT.resolvedQuality().detailCap());

            // De clip a espacio relativo a la camara: es lo que da la direccion del rayo por pixel.
            Matrix4f inverse = new Matrix4f(projection).mul(poseStack.last().pose()).invert();

            Vec3 cloud = level.getCloudColor(partialTick);
            Vec3 sky = level.getSkyColor(cameraPos, partialTick);
            double sunAngle = level.getSunAngle(partialTick);

            int pasos = switch (AtmosiaConfig.CLIENT.qualityProfile.get()) {
                case LOW -> 24;
                case HIGH -> 80;
                default -> 48;
            };

            ShaderInstance s = shader;
            RenderSystem.setShader(() -> s);
            RenderSystem.setShaderTexture(0, this.noiseId);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);

            set(s, "AtmInverseViewProj", inverse);
            setVec3(s, "AtmCameraPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
            setVec3(s, "AtmSkyColor", (float) sky.x, (float) sky.y, (float) sky.z);
            setVec3(s, "AtmCloudTint", (float) cloud.x, (float) cloud.y, (float) cloud.z);
            setVec3(s, "AtmSunDir", (float) -Math.sin(sunAngle), (float) Math.cos(sunAngle), 0.0F);
            setVec4(s, "AtmParams", (float) (double) AtmosiaConfig.CLIENT.coverageScale.get(),
                    (float) selector.maxDistance(), (float) seconds, pasos);

            CloudLayerDef[] layers = CloudLayerDef.DEFAULTS;
            setVec4(s, "AtmWind0",
                    (float) (layers[0].windOffsetX(seconds) * speedScale),
                    (float) (layers[0].windOffsetZ(seconds) * speedScale),
                    (float) (layers[1].windOffsetX(seconds) * speedScale),
                    (float) (layers[1].windOffsetZ(seconds) * speedScale));
            setVec4(s, "AtmWind1",
                    (float) (layers[2].windOffsetX(seconds) * speedScale),
                    (float) (layers[2].windOffsetZ(seconds) * speedScale), 0.0F, 0.0F);

            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            b.vertex(-1.0F, -1.0F, 0.0F).uv(0.0F, 0.0F).endVertex();
            b.vertex(1.0F, -1.0F, 0.0F).uv(1.0F, 0.0F).endVertex();
            b.vertex(1.0F, 1.0F, 0.0F).uv(1.0F, 1.0F).endVertex();
            b.vertex(-1.0F, 1.0F, 0.0F).uv(0.0F, 1.0F).endVertex();
            BufferUploader.drawWithShader(b.end());
            this.lastDrawCalls = 1;

            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } catch (RuntimeException e) {
            // Un shader que no anda no puede tumbar el juego: se marca y se cede el cielo.
            this.failed = true;
        }
    }

    private static void set(ShaderInstance s, String name, Matrix4f value) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(value);
        }
    }

    private static void setVec3(ShaderInstance s, String name, float x, float y, float z) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x, y, z);
        }
    }

    private static void setVec4(ShaderInstance s, String name, float x, float y, float z, float w) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x, y, z, w);
        }
    }

    public void close() {
        if (this.noiseTexture != null) {
            this.noiseTexture.close();
            this.noiseTexture = null;
            this.noiseId = null;
        }
    }

    @Override
    public String rendererName() {
        return "atmosia-raymarch";
    }

    @Override
    public int activeRegions() {
        return 0;
    }

    @Override
    public int queuedRegions() {
        return 0;
    }

    @Override
    public long verticesLastFrame() {
        return 4L;
    }

    @Override
    public int drawCallsLastFrame() {
        return this.lastDrawCalls;
    }

    @Override
    public long cacheBytes() {
        return 0L;
    }

    @Override
    public long gpuBytes() {
        return (long) NOISE_SIZE * NOISE_SIZE * 4L;
    }

    @Override
    public double lastRegionGenerationMillis() {
        return -1.0D;
    }
}
