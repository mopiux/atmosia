package dev.mopiux.atmosia.client.sprite;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.bench.CloudMetricsProvider;
import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.DensityField;
import dev.mopiux.atmosia.core.LodSelector;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.PuffField;
import dev.mopiux.atmosia.core.RegionKey;
import dev.mopiux.atmosia.core.VerticalFade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Nubes por sprites: bultos con textura que siempre miran a la camara.
 *
 * <h2>Que problema resuelve</h2>
 *
 * La tecnica de planos apilados parte el cielo en una grilla de regiones de 256 bloques y dibuja
 * cada una por separado. La mezcla con transparencia depende del orden, y al cruzar el limite entre
 * dos regiones el orden cambia de golpe: aparece una recta. Se corrigieron cinco causas distintas
 * de eso y siempre aparecio una sexta, porque el problema es de la estructura y no de los valores.
 *
 * Aca no hay grilla de geometria. Hay miles de bultos sueltos, y el error de orden -que sigue
 * existiendo, es inherente a la transparencia- se reparte entre ellos como ruido en vez de
 * alinearse en una recta. El ojo detecta una recta de un nivel de gris; no detecta ruido del mismo
 * nivel. Esa es toda la diferencia, y es suficiente.
 *
 * <h2>Como se ordena</h2>
 *
 * Todos los bultos visibles se ordenan de lejos a cerca por distancia real, no por region. Con
 * decenas de miles, un ordenamiento comun costaria milisegundos por frame, asi que se usa un
 * reparto en cubetas por distancia: es lineal, y dentro de una cubeta las diferencias de
 * profundidad son tan chicas que el orden no cambia el resultado de forma visible.
 *
 * SIN COMPILAR NI EJECUTAR contra el juego.
 */
public final class SpriteCloudRenderer implements CloudMetricsProvider {

    public static final ResourceLocation PUFF_TEXTURE =
            new ResourceLocation("atmosia", "textures/environment/puff.png");

    /** Cubetas de profundidad. Mas cubetas, orden mas fino y mas memoria. */
    private static final int BUCKETS = 384;

    /** Regiones de bultos que se siembran por frame. Sembrar es barato, pero no gratis. */
    private static final int SEEDS_PER_FRAME = 6;

    /** Tope de bultos dibujados por frame, por si la configuracion pide un domo enorme. */
    private static final int MAX_PUFFS = 60_000;

    private final Map<RegionKey, PuffField.Puff[]> cache = new HashMap<>();
    private final NoiseField noise;
    private final long seed;
    private final VerticalFade verticalFade = VerticalFade.defaults();

    private double appliedCoverageScale;
    private int lastPuffs;
    private int lastDrawCalls;

    /**
     * Buffer reutilizado. La geometria se arma cada frame -los bultos tienen que mirar a la camara
     * y el orden depende de donde este- pero el buffer de GPU se reutiliza en vez de recrearse.
     */
    @Nullable
    private VertexBuffer buffer;

    /** Cubetas reutilizadas entre frames: no se reserva memoria en el bucle de dibujo. */
    private final List<List<Entry>> buckets = new ArrayList<>(BUCKETS);

    private record Entry(PuffField.Puff puff, CloudLayerDef layer, double offsetX, double offsetZ,
                         float opacity) {
    }

    public SpriteCloudRenderer(long seed) {
        this.seed = seed;
        this.noise = new NoiseField(seed);
        this.appliedCoverageScale = AtmosiaConfig.CLIENT.coverageScale.get();
        for (int i = 0; i < BUCKETS; i++) {
            this.buckets.add(new ArrayList<>());
        }
    }

    public long seed() {
        return this.seed;
    }

    public void render(PoseStack poseStack, Matrix4f projection, Camera camera, ClientLevel level,
                       float partialTick) {
        double coverageScale = AtmosiaConfig.CLIENT.coverageScale.get();
        if (coverageScale != this.appliedCoverageScale) {
            this.appliedCoverageScale = coverageScale;
            this.cache.clear();
        }

        this.lastPuffs = 0;
        this.lastDrawCalls = 0;
        for (List<Entry> bucket : this.buckets) {
            bucket.clear();
        }

        double seconds = (level.getGameTime() + partialTick) / 20.0D;
        double speedScale = AtmosiaConfig.CLIENT.speedScale.get();
        Vec3 cameraPos = camera.getPosition();
        float pitch = camera.getXRot();

        LodSelector selector = LodSelector.forRenderDistance(
                Minecraft.getInstance().options.renderDistance().get(),
                AtmosiaConfig.CLIENT.resolvedQuality().distanceMultiplier(),
                AtmosiaConfig.CLIENT.resolvedQuality().detailCap());
        double maxDistance = selector.maxDistance();

        int sembradas = 0;
        CloudLayerDef[] layers = CloudLayerDef.DEFAULTS;
        for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
            CloudLayerDef layer = layers[layerIndex];
            float layerOpacity = this.verticalFade.opacity(cameraPos.y, layer, pitch);
            if (VerticalFade.isCulled(layerOpacity)) {
                continue;
            }

            double windX = layer.windOffsetX(seconds) * speedScale;
            double windZ = layer.windOffsetZ(seconds) * speedScale;
            double cloudCameraX = cameraPos.x - windX;
            double cloudCameraZ = cameraPos.z - windZ;

            int radius = (int) Math.ceil(maxDistance / RegionKey.REGION_SIZE) + 1;
            RegionKey center = RegionKey.of(layerIndex, cloudCameraX, cloudCameraZ);

            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    RegionKey key = new RegionKey(layerIndex, center.x() + dx, center.z() + dz);
                    double toX = key.centerX() - cloudCameraX;
                    double toZ = key.centerZ() - cloudCameraZ;
                    double distance = Math.sqrt(toX * toX + toZ * toZ);
                    if (distance > maxDistance + LodSelector.DRAW_MARGIN) {
                        continue;
                    }

                    PuffField.Puff[] puffs = this.cache.get(key);
                    if (puffs == null) {
                        if (sembradas >= SEEDS_PER_FRAME) {
                            continue;
                        }
                        puffs = this.sow(key, layer);
                        this.cache.put(key, puffs);
                        sembradas++;
                    }
                    if (puffs.length == 0) {
                        continue;
                    }

                    for (PuffField.Puff p : puffs) {
                        double wx = p.x() + windX - cameraPos.x;
                        double wy = p.y() - cameraPos.y;
                        double wz = p.z() + windZ - cameraPos.z;
                        double d = Math.sqrt(wx * wx + wy * wy + wz * wz);
                        if (d > maxDistance) {
                            continue;
                        }
                        int bucket = (int) (d / maxDistance * (BUCKETS - 1));
                        this.buckets.get(Math.max(0, Math.min(BUCKETS - 1, bucket)))
                                .add(new Entry(p, layer, windX, windZ, layerOpacity));
                    }
                }
            }
        }

        this.draw(poseStack, projection, camera, level, partialTick, maxDistance);
    }

    private PuffField.Puff[] sow(RegionKey key, CloudLayerDef layer) {
        DensityField field = new DensityField(this.noise, layer, this.appliedCoverageScale);
        List<PuffField.Puff> out = new ArrayList<>();
        PuffField.seed(field, layer, key.originX(), key.originZ(), RegionKey.REGION_SIZE,
                this.seed, out::add);
        return out.toArray(new PuffField.Puff[0]);
    }

    /**
     * Dibuja de lejos a cerca, recorriendo las cubetas en orden inverso.
     *
     * Todo en un solo draw call: los bultos no necesitan estar separados por region ni por capa,
     * y de hecho es importante que no lo esten. Mezclarlos entre si es lo que convierte el error
     * de orden en ruido en vez de una recta.
     */
    private void draw(PoseStack poseStack, Matrix4f projection, Camera camera, ClientLevel level,
                      float partialTick, double maxDistance) {
        Vec3 cloudColor = level.getCloudColor(partialTick);

        // Ejes de la camara: los bultos siempre la miran de frente.
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f right = new Vector3f(look).cross(up).normalize();
        Vector3f realUp = new Vector3f(right).cross(look).normalize();

        SpriteRenderType.CLOUDS.setupRenderState();
        RenderSystem.setShaderTexture(0, PUFF_TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f pose = poseStack.last().pose();

        Vec3 cameraPos = camera.getPosition();
        int emitidos = 0;
        for (int b = BUCKETS - 1; b >= 0 && emitidos < MAX_PUFFS; b--) {
            for (Entry e : this.buckets.get(b)) {
                if (emitidos >= MAX_PUFFS) {
                    break;
                }
                PuffField.Puff p = e.puff();
                float cx = (float) (p.x() + e.offsetX() - cameraPos.x);
                float cy = (float) (p.y() - cameraPos.y);
                float cz = (float) (p.z() + e.offsetZ() - cameraPos.z);

                double d = Math.sqrt(cx * cx + cy * cy + cz * cz);
                float fade = distanceFade(d, maxDistance);
                float alpha = p.alpha() * e.opacity() * fade;
                if (alpha <= 0.004F) {
                    continue;
                }

                float r = p.radius();
                float rx = right.x() * r;
                float ry = right.y() * r;
                float rz = right.z() * r;
                float ux = realUp.x() * r;
                float uy = realUp.y() * r;
                float uz = realUp.z() * r;

                float sh = p.shade();
                float cr = (float) cloudColor.x * e.layer().red() * sh;
                float cg = (float) cloudColor.y * e.layer().green() * sh;
                float cb = (float) cloudColor.z * e.layer().blue() * sh;

                // Se transforma el vertice con la pila del evento aca mismo, para poder dibujar con
                // matrices explicitas y no depender del estado global de RenderSystem.
                builder.vertex(pose, cx - rx - ux, cy - ry - uy, cz - rz - uz).uv(0, 1)
                        .color(cr, cg, cb, alpha).endVertex();
                builder.vertex(pose, cx + rx - ux, cy + ry - uy, cz + rz - uz).uv(1, 1)
                        .color(cr, cg, cb, alpha).endVertex();
                builder.vertex(pose, cx + rx + ux, cy + ry + uy, cz + rz + uz).uv(1, 0)
                        .color(cr, cg, cb, alpha).endVertex();
                builder.vertex(pose, cx - rx + ux, cy - ry + uy, cz - rz + uz).uv(0, 0)
                        .color(cr, cg, cb, alpha).endVertex();
                emitidos++;
            }
        }

        this.lastPuffs = emitidos;
        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (emitidos > 0) {
            if (this.buffer == null) {
                this.buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            this.buffer.bind();
            this.buffer.upload(rendered);
            // Matrices explicitas, igual que la tecnica de planos: es la ruta que ya se sabe que
            // funciona, y no toca el estado global.
            this.buffer.drawWithShader(new Matrix4f(), projection,
                    GameRenderer.getPositionTexColorShader());
            VertexBuffer.unbind();
            this.lastDrawCalls = 1;
        } else {
            rendered.release();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        SpriteRenderType.CLOUDS.clearRenderState();
    }

    /**
     * Desvanecimiento del borde del domo, POR BULTO y no por region.
     *
     * Un bulto mide unas decenas de bloques, asi que el borde queda suave y desordenado en vez de
     * ser un poligono escalonado de 256 bloques como en la tecnica de planos.
     */
    private static float distanceFade(double distance, double maxDistance) {
        double start = maxDistance * 0.55D;
        if (distance <= start) {
            return 1.0F;
        }
        if (distance >= maxDistance) {
            return 0.0F;
        }
        double t = (distance - start) / (maxDistance - start);
        return (float) (1.0D - t * t * (3.0D - 2.0D * t));
    }

    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
        this.cache.clear();
        for (List<Entry> bucket : this.buckets) {
            bucket.clear();
        }
    }

    @Override
    public String rendererName() {
        return "atmosia-sprites";
    }

    @Override
    public int activeRegions() {
        return this.cache.size();
    }

    @Override
    public int queuedRegions() {
        return 0;
    }

    @Override
    public long verticesLastFrame() {
        return (long) this.lastPuffs * 4L;
    }

    @Override
    public int drawCallsLastFrame() {
        return this.lastDrawCalls;
    }

    @Override
    public long cacheBytes() {
        long total = 0L;
        for (PuffField.Puff[] p : this.cache.values()) {
            total += (long) p.length * 24L;
        }
        return total;
    }

    @Override
    public long gpuBytes() {
        // No hay buffers persistentes: la geometria se arma cada frame.
        return 0L;
    }

    @Override
    public double lastRegionGenerationMillis() {
        return -1.0D;
    }
}
