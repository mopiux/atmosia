package dev.mopiux.atmosia.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.bench.CloudMetricsProvider;
import dev.mopiux.atmosia.core.CloudBudget;
import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.DensityField;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.LodSelector;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.RegionKey;
import dev.mopiux.atmosia.core.RegionPriority;
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
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renderer de nubes: caché, LOD, culling, presupuesto y dibujo.
 *
 * El movimiento se resuelve con un desplazamiento y nunca regenerando geometría. Las regiones
 * viven en espacio de nube —coordenadas con el viento ya descontado— así que su densidad no cambia
 * jamás; lo único que cambia con el tiempo es dónde se dibujan y cuáles caen dentro del rango. Eso
 * satisface de una sola vez el requisito de movimiento de la Sección 10 y el de no regenerar de la
 * Sección 5.1.
 *
 * SIN COMPILAR NI EJECUTAR. Los puntos donde la API de 1.20.1 hay que confirmarla están marcados
 * con VERIFICAR.
 */
public final class CloudRenderer implements CloudMetricsProvider {

    private final Map<RegionKey, RegionMesh> cache = new HashMap<>();
    private final List<Entry> drawList = new ArrayList<>();
    private final CloudBudget budget;
    private final GenerationQueue queue;
    private final VerticalFade verticalFade = VerticalFade.defaults();
    private final NoiseField noise;
    private final long seed;

    /** Tope de resultados ya calculados esperando construcción. */
    private static final int MAX_DEFERRED = 32;

    private final List<DensityJob.Result> deferred = new ArrayList<>();

    private long frame;
    private int lastQuads;
    private int lastDrawCalls;
    private double lastGenerationMillis = -1.0D;

    private record Entry(RegionMesh mesh, double distance, float opacity,
                         double offsetX, double offsetZ) {
    }

    public CloudRenderer(long seed) {
        this.seed = seed;
        this.noise = new NoiseField(seed);
        this.budget = new CloudBudget(
                AtmosiaConfig.CLIENT.regionsPerFrame.get(),
                AtmosiaConfig.CLIENT.quadsPerFrame.get(),
                AtmosiaConfig.CLIENT.maxCachedRegions.get());
        this.queue = new GenerationQueue(AtmosiaConfig.CLIENT.generationThreads.get());
    }

    public long seed() {
        return this.seed;
    }

    /**
     * Dibuja las nubes. Se llama una vez por frame desde el evento de render de nivel.
     *
     * @param poseStack  pila de transformación del evento, con la cámara ya orientada
     * @param projection matriz de proyección del frame
     * @param frustum    frustum del frame, o null si no está disponible
     */
    public void render(PoseStack poseStack, Matrix4f projection, Camera camera, ClientLevel level,
                       float partialTick, @Nullable Frustum frustum) {
        this.frame++;
        this.budget.beginFrame();
        this.drawList.clear();
        this.lastQuads = 0;
        this.lastDrawCalls = 0;

        // El tiempo del mundo, no el reloj del cliente: con el reloj del cliente las nubes saltan
        // al reconectar, se desincronizan del ciclo día/noche y siguen avanzando en pausa.
        double seconds = (level.getGameTime() + partialTick) / 20.0D;
        double speedScale = AtmosiaConfig.CLIENT.speedScale.get();

        Vec3 cameraPos = camera.getPosition();
        LodSelector selector = LodSelector.forRenderDistance(
                Minecraft.getInstance().options.renderDistance().get(),
                AtmosiaConfig.CLIENT.distanceMultiplier.get());

        Vector3f look = camera.getLookVector();
        float pitch = camera.getXRot();

        this.collectAndQueue(cameraPos, look, pitch, selector, seconds, speedScale, frustum);
        this.consumeCompletedWithinBudget();
        this.draw(poseStack, projection, cameraPos, level, partialTick, camera);
        this.evict();
    }

    /**
     * Recorre las regiones candidatas, dibuja las que ya están y encola las que faltan.
     *
     * Se recorre por capa porque cada una tiene su propia deriva y, por lo tanto, su propia grilla
     * de regiones en espacio de nube.
     */
    private void collectAndQueue(Vec3 cameraPos, Vector3f look, float pitch, LodSelector selector,
                                 double seconds, double speedScale, @Nullable Frustum frustum) {
        CloudLayerDef[] layers = CloudLayerDef.DEFAULTS;
        List<Pending> pending = new ArrayList<>();

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

            int radius = (int) Math.ceil(selector.maxDistance() / RegionKey.REGION_SIZE) + 1;
            RegionKey center = RegionKey.of(layerIndex, cloudCameraX, cloudCameraZ);

            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    RegionKey key = new RegionKey(layerIndex, center.x() + dx, center.z() + dz);

                    double toX = key.centerX() - cloudCameraX;
                    double toZ = key.centerZ() - cloudCameraZ;
                    double distance = Math.sqrt(toX * toX + toZ * toZ);

                    LodLevel lod = selector.levelFor(distance);
                    if (lod == null) {
                        continue;
                    }
                    if (layerIndex >= lod.layers()) {
                        // A distancia no se mantienen todas las capas (Sección 7.1): las de arriba
                        // son las que menos se notan al desaparecer, por eso se van primero.
                        continue;
                    }

                    double dot = distance < 1.0E-6D ? 1.0D : (toX * look.x() + toZ * look.z()) / distance;
                    boolean visible = frustum == null ? dot > -0.35D : this.inFrustum(frustum, key, layer, windX, windZ);
                    int priority = RegionPriority.classify(distance, selector, visible, dot);

                    RegionMesh mesh = this.cache.get(key);
                    if (mesh != null) {
                        mesh.markUsed(this.frame);
                        if (visible && !mesh.isEmpty()) {
                            float opacity = layerOpacity * selector.distanceFade(distance);
                            if (!VerticalFade.isCulled(opacity)) {
                                this.drawList.add(new Entry(mesh, distance, opacity, windX, windZ));
                            }
                        }
                        if (mesh.lod() == lod) {
                            continue;
                        }
                        // El LOD cambió: se encola la nueva versión, pero se sigue dibujando la
                        // vieja mientras tanto. Dejar de dibujarla abriría un agujero en el cielo
                        // justo al cruzar el umbral de distancia, que es el popping que la
                        // Sección 7.1 pide evitar.
                    }

                    if (RegionPriority.shouldGenerate(priority) && !this.queue.isInFlight(key)) {
                        pending.add(new Pending(key, layer, lod, RegionPriority.sortKey(priority, distance)));
                    }
                }
            }
        }

        // Más urgente primero: lo que no entre hoy se reevalúa el frame que viene, ya con la
        // prioridad actualizada a donde esté mirando el jugador.
        pending.sort((a, b) -> Long.compare(a.sortKey, b.sortKey));
        for (Pending item : pending) {
            if (this.queue.isSaturated()) {
                break;
            }
            this.queue.submit(new DensityJob(item.key, item.layer, item.lod, this.noise));
        }
    }

    private record Pending(RegionKey key, CloudLayerDef layer, LodLevel lod, long sortKey) {
    }

    /**
     * VERIFICAR: la caja de la región en coordenadas de mundo debe coincidir con lo que espera el
     * frustum de 1.20.1. Si la comprobación resulta incorrecta, el síntoma es que desaparezcan
     * nubes que deberían verse al girar la cámara.
     */
    private boolean inFrustum(Frustum frustum, RegionKey key, CloudLayerDef layer, double windX, double windZ) {
        double minX = key.originX() + windX;
        double minZ = key.originZ() + windZ;
        return frustum.isVisible(new net.minecraft.world.phys.AABB(
                minX, layer.baseHeight(), minZ,
                minX + RegionKey.REGION_SIZE, layer.topHeight(), minZ + RegionKey.REGION_SIZE));
    }

    /**
     * Construye mallas con los resultados listos, hasta agotar el presupuesto del frame.
     *
     * Lo que no entra no se descarta: el cálculo de densidad ya está pagado, así que se guarda y
     * se construye en el frame siguiente, antes que nada nuevo. Solo se tira si la lista de
     * diferidos crece demasiado, y en ese caso la región se vuelve a calcular cuando haga falta.
     */
    private void consumeCompletedWithinBudget() {
        List<DensityJob.Result> carried = new ArrayList<>(this.deferred);
        this.deferred.clear();

        for (int i = 0; i < carried.size(); i++) {
            if (!this.tryBuild(carried.get(i))) {
                this.deferRemaining(carried, i);
                return;
            }
        }

        DensityJob.Result result;
        while ((result = this.queue.poll()) != null) {
            if (!this.tryBuild(result)) {
                return;
            }
        }
    }

    /** Construye una región si entra en el presupuesto. Si no entra, la difiere y devuelve false. */
    private boolean tryBuild(DensityJob.Result result) {
        int quads = result.estimatedQuads();
        if (!this.budget.canGenerate(quads) && !this.budget.canGenerateAtLeastOne()) {
            this.defer(result);
            return false;
        }

        long start = System.nanoTime();
        RegionMesh mesh = RegionMeshBuilder.build(result, this.noise, this.frame);
        this.lastGenerationMillis = (System.nanoTime() - start) / 1_000_000.0D;

        RegionMesh previous = this.cache.put(mesh.key(), mesh);
        if (previous != null) {
            previous.close();
        }
        this.budget.recordGenerated(quads);
        return true;
    }

    private void deferRemaining(List<DensityJob.Result> results, int from) {
        for (int i = from; i < results.size(); i++) {
            this.defer(results.get(i));
        }
    }

    private void defer(DensityJob.Result result) {
        if (this.deferred.size() < MAX_DEFERRED) {
            this.deferred.add(result);
        }
    }

    /** Dibuja de lejos a cerca: con transparencia, el orden cambia el resultado. */
    private void draw(PoseStack poseStack, Matrix4f projection, Vec3 cameraPos, ClientLevel level,
                      float partialTick, Camera camera) {
        if (this.drawList.isEmpty()) {
            return;
        }
        this.drawList.sort((a, b) -> Double.compare(b.distance(), a.distance()));

        Vec3 cloudColor = level.getCloudColor(partialTick);
        float scatter = this.forwardScatter(level, camera, partialTick);

        AtmosiaRenderType.CLOUDS.setupRenderState();
        var shader = GameRenderer.getPositionColorShader();

        for (Entry entry : this.drawList) {
            VertexBuffer buffer = entry.mesh().buffer();
            if (buffer == null) {
                continue;
            }

            poseStack.pushPose();
            // VERIFICAR: en 1.20.1 la pila del evento viene sin la traslación de cámara aplicada,
            // así que se resta acá. Si las nubes aparecen pegadas a la cámara o en el lugar
            // equivocado, este es el punto a revisar.
            poseStack.translate(
                    entry.mesh().key().originX() + entry.offsetX() - cameraPos.x,
                    -cameraPos.y,
                    entry.mesh().key().originZ() + entry.offsetZ() - cameraPos.z);

            RenderSystem.setShaderColor(
                    (float) cloudColor.x * scatter,
                    (float) cloudColor.y * scatter,
                    (float) cloudColor.z * scatter,
                    entry.opacity());

            buffer.bind();
            buffer.drawWithShader(poseStack.last().pose(), projection, shader);

            poseStack.popPose();
            this.lastQuads += entry.mesh().quads();
            this.lastDrawCalls++;
        }

        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        AtmosiaRenderType.CLOUDS.clearRenderState();
    }

    /** Brillo extra cuando se mira hacia el sol (Sección 11.2). */
    private float forwardScatter(ClientLevel level, Camera camera, float partialTick) {
        double sunAngle = level.getSunAngle(partialTick);
        Vector3f look = camera.getLookVector();
        // El sol recorre el plano XY: basta su dirección proyectada contra la vista.
        double sunX = -Math.sin(sunAngle);
        double sunY = Math.cos(sunAngle);
        double dot = look.x() * sunX + look.y() * sunY;
        return DensityField.forwardScatter(dot);
    }

    /** Descarta lo más viejo cuando la caché pasa su tamaño máximo (Sección 5.2). */
    private void evict() {
        int excess = this.cache.size() - this.budget.maxCachedRegions();
        if (excess <= 0) {
            return;
        }
        List<RegionMesh> byAge = new ArrayList<>(this.cache.values());
        byAge.sort((a, b) -> Long.compare(a.lastUsedFrame(), b.lastUsedFrame()));
        for (int i = 0; i < excess && i < byAge.size(); i++) {
            RegionMesh mesh = byAge.get(i);
            this.cache.remove(mesh.key());
            mesh.close();
        }
    }

    /** Libera todo. Al cambiar de mundo o de configuración. */
    public void close() {
        this.queue.shutdown();
        for (RegionMesh mesh : this.cache.values()) {
            mesh.close();
        }
        this.cache.clear();
        this.drawList.clear();
        this.deferred.clear();
    }

    // -------------------------------------------------------------------------------------
    // Métricas para el harness de benchmark
    // -------------------------------------------------------------------------------------

    @Override
    public String rendererName() {
        return "atmosia";
    }

    @Override
    public int activeRegions() {
        return this.cache.size();
    }

    @Override
    public int queuedRegions() {
        return this.queue.inFlightCount();
    }

    @Override
    public long verticesLastFrame() {
        return (long) this.lastQuads * 4L;
    }

    @Override
    public int drawCallsLastFrame() {
        return this.lastDrawCalls;
    }

    @Override
    public long cacheBytes() {
        // La densidad no se conserva tras construir la malla, así que la caché en memoria
        // principal es solo la contabilidad de las entradas.
        return (long) this.cache.size() * 64L;
    }

    @Override
    public long gpuBytes() {
        long total = 0L;
        for (RegionMesh mesh : this.cache.values()) {
            total += mesh.approximateGpuBytes();
        }
        return total;
    }

    @Override
    public double lastRegionGenerationMillis() {
        return this.lastGenerationMillis;
    }
}
