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
import dev.mopiux.atmosia.core.MotionPrefetch;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.QualityProfile;
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
 * Renderer de nubes: cache, LOD, culling, presupuesto y dibujo.
 *
 * El movimiento se resuelve con un desplazamiento y nunca regenerando geometria. Las regiones
 * viven en espacio de nube -coordenadas con el viento ya descontado- asi que su densidad no cambia
 * jamas; lo unico que cambia con el tiempo es donde se dibujan y cuales caen dentro del rango. Eso
 * satisface de una sola vez el requisito de movimiento de la Seccion 10 y el de no regenerar de la
 * Seccion 5.1.
 *
 * SIN COMPILAR NI EJECUTAR. Los puntos donde la API de 1.20.1 hay que confirmarla estan marcados
 * con VERIFICAR.
 */
public final class CloudRenderer implements CloudMetricsProvider {

    private final Map<RegionKey, RegionMesh> cache = new HashMap<>();
    private final List<Entry> drawList = new ArrayList<>();
    private final GenerationQueue queue;

    /** No es final: el perfil grafico se cambia en caliente desde el menu. */
    private CloudBudget budget;

    /** Los valores con los que se construyo lo que hay en cache ahora mismo. */
    private QualityProfile.Settings appliedQuality;
    private double appliedCoverageScale;
    private final VerticalFade verticalFade = VerticalFade.defaults();
    private final MotionPrefetch prefetch = new MotionPrefetch();
    private final NoiseField noise;
    private final long seed;

    /** Tope de resultados ya calculados esperando construccion. */
    private static final int MAX_DEFERRED = 32;

    private final List<DensityJob.Result> deferred = new ArrayList<>();

    /**
     * Con que orden de mezcla esta horneada cada capa ahora mismo.
     *
     * Cambia solo cuando la camara cruza la capa, que es raro, y el cambio se propaga region por
     * region igual que un cambio de nivel de detalle: sin huecos y dentro del presupuesto.
     */
    private final boolean[] layerTopDown = new boolean[CloudLayerDef.DEFAULTS.length];

    /** Margen para no rehornear todo el tiempo cuando la camara queda justo en el medio. */
    private static final double CROSSING_MARGIN = 12.0D;

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
        this.appliedQuality = AtmosiaConfig.CLIENT.resolvedQuality();
        this.appliedCoverageScale = AtmosiaConfig.CLIENT.coverageScale.get();
        this.budget = budgetFor(this.appliedQuality);
        this.queue = new GenerationQueue(AtmosiaConfig.CLIENT.generationThreads.get());
        java.util.Arrays.fill(this.layerTopDown, true);
    }

    private static CloudBudget budgetFor(QualityProfile.Settings quality) {
        return new CloudBudget(quality.regionsPerFrame(), quality.quadsPerFrame(),
                quality.maxCachedRegions());
    }

    /**
     * Relee la configuracion y reacciona a lo que haya cambiado desde el frame anterior.
     *
     * La distincion importante es cual de los dos ajustes obliga a tirar la cache. El perfil solo
     * cambia que nivel de detalle le toca a cada region, y eso el renderer ya lo detecta region por
     * region y lo reemplaza sin huecos. La cantidad de nubes cambia la densidad misma: las mallas
     * que ya estan en memoria describen un cielo que ya no es el pedido, y no hay forma de saberlo
     * mirando su clave. Esas hay que rehacerlas.
     */
    private void applyConfigChanges() {
        QualityProfile.Settings quality = AtmosiaConfig.CLIENT.resolvedQuality();
        if (!quality.equals(this.appliedQuality)) {
            this.appliedQuality = quality;
            this.budget = budgetFor(quality);
        }

        double coverageScale = AtmosiaConfig.CLIENT.coverageScale.get();
        if (coverageScale != this.appliedCoverageScale) {
            this.appliedCoverageScale = coverageScale;
            this.flushGeometry();
        }
    }

    /** Tira toda la geometria cacheada. El cielo se vuelve a llenar con el presupuesto de siempre. */
    private void flushGeometry() {
        for (RegionMesh mesh : this.cache.values()) {
            mesh.close();
        }
        this.cache.clear();
        this.drawList.clear();
        this.deferred.clear();
    }

    public long seed() {
        return this.seed;
    }

    /**
     * Dibuja las nubes. Se llama una vez por frame desde el evento de render de nivel.
     *
     * @param poseStack  pila de transformacion del evento, con la camara ya orientada
     * @param projection matriz de proyeccion del frame
     * @param frustum    frustum del frame, o null si no esta disponible
     */
    public void render(PoseStack poseStack, Matrix4f projection, Camera camera, ClientLevel level,
                       float partialTick, @Nullable Frustum frustum) {
        this.frame++;
        this.applyConfigChanges();
        this.budget.beginFrame();
        this.drawList.clear();
        this.lastQuads = 0;
        this.lastDrawCalls = 0;

        // El tiempo del mundo, no el reloj del cliente: con el reloj del cliente las nubes saltan
        // al reconectar, se desincronizan del ciclo dia/noche y siguen avanzando en pausa.
        double seconds = (level.getGameTime() + partialTick) / 20.0D;
        double speedScale = AtmosiaConfig.CLIENT.speedScale.get();

        Vec3 cameraPos = camera.getPosition();
        this.prefetch.update(cameraPos.x, cameraPos.z, seconds);

        LodSelector selector = LodSelector.forRenderDistance(
                Minecraft.getInstance().options.renderDistance().get(),
                this.appliedQuality.distanceMultiplier(),
                this.appliedQuality.detailCap());

        Vector3f look = camera.getLookVector();
        float pitch = camera.getXRot();

        this.collectAndQueue(cameraPos, look, pitch, selector, seconds, speedScale, frustum);
        this.consumeCompletedWithinBudget();
        this.draw(poseStack, projection, cameraPos, level, partialTick, camera);
        this.evict();
    }

    /**
     * Recorre las regiones candidatas, dibuja las que ya estan y encola las que faltan.
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

            // Desde abajo, los cortes altos estan mas lejos y van primero; desde arriba, al reves.
            // El margen evita rehornear en cada frame cuando la camara se queda en el medio.
            boolean topDown = this.layerTopDown[layerIndex];
            if (cameraPos.y < layer.centerHeight() - CROSSING_MARGIN) {
                topDown = true;
            } else if (cameraPos.y > layer.centerHeight() + CROSSING_MARGIN) {
                topDown = false;
            }
            this.layerTopDown[layerIndex] = topDown;

            float layerOpacity = this.verticalFade.opacity(cameraPos.y, layer, pitch);
            if (VerticalFade.isCulled(layerOpacity)) {
                continue;
            }

            double windX = layer.windOffsetX(seconds) * speedScale;
            double windZ = layer.windOffsetZ(seconds) * speedScale;
            double cloudCameraX = cameraPos.x - windX;
            double cloudCameraZ = cameraPos.z - windZ;

            // Posicion proyectada: la misma que la real mientras no se vaya rapido.
            double aheadX = cloudCameraX + this.prefetch.leadX();
            double aheadZ = cloudCameraZ + this.prefetch.leadZ();

            // El barrido se agranda con el adelanto, si no no habria nada nuevo que encontrar
            // adelante: las regiones que se quieren anticipar estan, por definicion, fuera del domo.
            double scanRange = selector.maxDistance() + this.prefetch.leadLength();
            int radius = (int) Math.ceil(scanRange / RegionKey.REGION_SIZE) + 1;
            RegionKey center = RegionKey.of(layerIndex, cloudCameraX, cloudCameraZ);

            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    RegionKey key = new RegionKey(layerIndex, center.x() + dx, center.z() + dz);

                    double toX = key.centerX() - cloudCameraX;
                    double toZ = key.centerZ() - cloudCameraZ;
                    double distance = Math.sqrt(toX * toX + toZ * toZ);

                    // Lo que se dibuja se decide con la posicion real; lo que se genera, con la
                    // proyectada. Adelantar tambien el dibujo moveria el domo respecto de la
                    // camara y dejaria un borde a la vista por detras.
                    double aheadDX = key.centerX() - aheadX;
                    double aheadDZ = key.centerZ() - aheadZ;
                    double aheadDistance = Math.sqrt(aheadDX * aheadDX + aheadDZ * aheadDZ);

                    LodLevel drawLod = selector.levelFor(distance);
                    LodLevel wantedLod = selector.levelFor(aheadDistance);
                    if (drawLod == null && wantedLod == null) {
                        continue;
                    }
                    // El nivel a construir es el que va a hacer falta al llegar. Si no se va a
                    // ningun lado, los dos valen lo mismo y no cambia nada.
                    LodLevel lod = wantedLod != null ? wantedLod : drawLod;
                    if (layerIndex >= lod.layers()) {
                        // A distancia no se mantienen todas las capas (Seccion 7.1): las de arriba
                        // son las que menos se notan al desaparecer, por eso se van primero.
                        continue;
                    }

                    double dot = distance < 1.0E-6D ? 1.0D : (toX * look.x() + toZ * look.z()) / distance;
                    boolean visible = drawLod != null
                            && (frustum == null ? dot > -0.35D : this.inFrustum(frustum, key, layer, windX, windZ));
                    // La prioridad usa la distancia proyectada: es lo que pone adelante de la cola
                    // lo que el jugador va a necesitar, en vez de lo que ya tiene encima.
                    int priority = RegionPriority.classify(aheadDistance, selector, visible, dot);

                    RegionMesh mesh = this.cache.get(key);
                    if (mesh != null) {
                        mesh.markUsed(this.frame);
                        if (visible && !mesh.isEmpty()) {
                            float opacity = layerOpacity * selector.distanceFade(distance);
                            if (!VerticalFade.isCulled(opacity)) {
                                this.drawList.add(new Entry(mesh, distance, opacity, windX, windZ));
                            }
                        }
                        if (mesh.lod() == lod && mesh.topDown() == topDown) {
                            continue;
                        }
                        // El LOD cambio: se encola la nueva version, pero se sigue dibujando la
                        // vieja mientras tanto. Dejar de dibujarla abriria un agujero en el cielo
                        // justo al cruzar el umbral de distancia, que es el popping que la
                        // Seccion 7.1 pide evitar.
                    }

                    if (RegionPriority.shouldGenerate(priority) && !this.queue.isInFlight(key)) {
                        pending.add(new Pending(key, layer, lod, topDown,
                                RegionPriority.sortKey(priority, aheadDistance)));
                    }
                }
            }
        }

        // Mas urgente primero: lo que no entre hoy se reevalua el frame que viene, ya con la
        // prioridad actualizada a donde este mirando el jugador.
        pending.sort((a, b) -> Long.compare(a.sortKey, b.sortKey));
        for (Pending item : pending) {
            if (this.queue.isSaturated()) {
                break;
            }
            this.queue.submit(new DensityJob(item.key, item.layer, item.lod, this.noise,
                    this.appliedCoverageScale, item.topDown()));
        }
    }

    private record Pending(RegionKey key, CloudLayerDef layer, LodLevel lod, boolean topDown,
                           long sortKey) {
    }

    /**
     * VERIFICAR: la caja de la region en coordenadas de mundo debe coincidir con lo que espera el
     * frustum de 1.20.1. Si la comprobacion resulta incorrecta, el sintoma es que desaparezcan
     * nubes que deberian verse al girar la camara.
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
     * Lo que no entra no se descarta: el calculo de densidad ya esta pagado, asi que se guarda y
     * se construye en el frame siguiente, antes que nada nuevo. Solo se tira si la lista de
     * diferidos crece demasiado, y en ese caso la region se vuelve a calcular cuando haga falta.
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

    /** Construye una region si entra en el presupuesto. Si no entra, la difiere y devuelve false. */
    private boolean tryBuild(DensityJob.Result result) {
        if (result.job().coverageScale() != this.appliedCoverageScale) {
            // Se calculo con otra cantidad de nubes y llego despues del cambio. Construirla meteria
            // en el cielo un pedazo del cielo anterior; se descarta y se vuelve a pedir cuando haga
            // falta, que es en el mismo frame.
            return true;
        }
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
            // VERIFICAR: en 1.20.1 la pila del evento viene sin la traslacion de camara aplicada,
            // asi que se resta aca. Si las nubes aparecen pegadas a la camara o en el lugar
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

    /** Brillo extra cuando se mira hacia el sol (Seccion 11.2). */
    private float forwardScatter(ClientLevel level, Camera camera, float partialTick) {
        double sunAngle = level.getSunAngle(partialTick);
        Vector3f look = camera.getLookVector();
        // El sol recorre el plano XY: basta su direccion proyectada contra la vista.
        double sunX = -Math.sin(sunAngle);
        double sunY = Math.cos(sunAngle);
        double dot = look.x() * sunX + look.y() * sunY;
        return DensityField.forwardScatter(dot);
    }

    /** Descarta lo mas viejo cuando la cache pasa su tamano maximo (Seccion 5.2). */
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

    /** Libera todo. Al cambiar de mundo o de configuracion. */
    public void close() {
        this.prefetch.reset();
        this.queue.shutdown();
        this.flushGeometry();
    }

    // -------------------------------------------------------------------------------------
    // Metricas para el harness de benchmark
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
        // La densidad no se conserva tras construir la malla, asi que la cache en memoria
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
