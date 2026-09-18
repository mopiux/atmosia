package dev.mopiux.atmosia.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comprobaciones del núcleo procedural: ruido, densidad, LOD, fade vertical, prioridad y
 * presupuesto. Nada de esto depende de Minecraft, así que se puede verificar de verdad.
 *
 * Igual que {@code BenchSmokeTest}, es un {@code main} sin dependencias:
 *
 * <pre>
 * javac --release 17 -d /tmp/atmosia-core src/main/java/dev/mopiux/atmosia/core/*.java \
 *     src/test/java/dev/mopiux/atmosia/core/CoreSmokeTest.java
 * java -cp /tmp/atmosia-core dev.mopiux.atmosia.core.CoreSmokeTest
 * </pre>
 */
public final class CoreSmokeTest {

    static int fails = 0;

    static void check(String name, boolean ok, Object got) {
        System.out.printf(Locale.ROOT, "%-52s %s  (%s)%n", name, ok ? "OK" : "FALLA", got);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) {
        noise();
        density();
        lod();
        fade();
        priority();
        budget();
        regions();

        System.out.println(fails == 0 ? "\nTODO OK" : "\n" + fails + " FALLAS");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void noise() {
        NoiseField n = new NoiseField(1234L);

        // Determinismo: es el requisito explícito de la Sección 4.
        check("ruido determinista", n.fbm(12.5, -7.25) == n.fbm(12.5, -7.25), n.fbm(12.5, -7.25));

        NoiseField other = new NoiseField(9999L);
        check("otra seed, otro cielo", n.fbm(3.0, 3.0) != other.fbm(3.0, 3.0), other.fbm(3.0, 3.0));

        double min = 1.0, max = 0.0, sum = 0.0;
        int samples = 0;
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                double v = n.fbm(x * 0.37, z * 0.37);
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
                samples++;
            }
        }
        check("ruido dentro de [0,1]", min >= 0.0 && max <= 1.0, min + " .. " + max);
        check("media cerca de 0.5", Math.abs(sum / samples - 0.5) < 0.05, sum / samples);
        check("usa todo el rango", max - min > 0.5, max - min);

        // Continuidad: sin saltos entre celdas, o se verían las costuras de la grilla.
        double worst = 0.0;
        for (int i = 0; i < 2000; i++) {
            double x = i * 0.01;
            worst = Math.max(worst, Math.abs(n.fbm(x, 4.0) - n.fbm(x + 0.01, 4.0)));
        }
        check("continuo (sin costuras de celda)", worst < 0.15, worst);

        // Precisión lejos del origen: el hash trabaja sobre enteros, no sobre floats grandes.
        double far = n.fbm(29_000_000.5, 29_000_000.5);
        check("estable lejos del origen", far == n.fbm(29_000_000.5, 29_000_000.5) && far >= 0.0 && far <= 1.0, far);
    }

    static void density() {
        NoiseField n = new NoiseField(7L);
        DensityField field = new DensityField(n, CloudLayerDef.MID);

        double d = field.densityAt(1000.0, 2000.0);
        check("densidad en [0,1]", d >= 0.0 && d <= 1.0, d);

        CloudLayerDef more = new CloudLayerDef("t", 192, 20, 1, 1, 1, 0, 0, 0.8, 420);
        CloudLayerDef less = new CloudLayerDef("t", 192, 20, 1, 1, 1, 0, 0, 0.2, 420);
        int moreWins = 0;
        for (int i = 0; i < 500; i++) {
            double x = i * 13.7;
            double a = new DensityField(n, more).densityAt(x, 0);
            double b = new DensityField(n, less).densityAt(x, 0);
            if (a >= b) {
                moreWins++;
            }
        }
        check("mas cobertura => mas nube, siempre", moreWins == 500, moreWins + "/500");

        double center = field.sliceThreshold(3, 8);
        double top = field.sliceThreshold(7, 8);
        double bottom = field.sliceThreshold(0, 8);
        check("umbral: centro < extremos", center < top && center < bottom,
                bottom + " / " + center + " / " + top);
        check("perfil simetrico", Math.abs(top - bottom) < 1e-9, top - bottom);

        boolean inside = true;
        for (int i = 0; i < 8; i++) {
            double y = field.sliceHeight(i, 8);
            inside &= y > CloudLayerDef.MID.baseHeight() && y < CloudLayerDef.MID.topHeight();
        }
        check("slices dentro de la capa", inside, field.sliceHeight(0, 8) + " .. " + field.sliceHeight(7, 8));
        check("slices ordenados de abajo hacia arriba",
                field.sliceHeight(0, 8) < field.sliceHeight(7, 8), field.sliceHeight(7, 8));

        check("sin nube bajo el umbral", field.cellAlpha(0.10, 0.20) == 0.0F, field.cellAlpha(0.10, 0.20));
        check("alpha crece con la densidad",
                field.cellAlpha(0.25, 0.20) < field.cellAlpha(0.35, 0.20), field.cellAlpha(0.35, 0.20));
        check("alpha satura en 1", field.cellAlpha(0.99, 0.20) == 1.0F, field.cellAlpha(0.99, 0.20));

        check("base mas oscura que techo",
                field.shade(0.5, 0, 8) < field.shade(0.5, 7, 8),
                field.shade(0.5, 0, 8) + " / " + field.shade(0.5, 7, 8));
        check("sombreado en rango util",
                field.shade(1.0, 0, 8) > 0.4F && field.shade(0.0, 7, 8) <= 1.0F, field.shade(1.0, 0, 8));

        check("sin brillo de espaldas al sol", DensityField.forwardScatter(-0.9) == 1.0F,
                DensityField.forwardScatter(-0.9));
        check("brillo mirando al sol", DensityField.forwardScatter(1.0) > 1.2F,
                DensityField.forwardScatter(1.0));
    }

    static void lod() {
        LodSelector sel = LodSelector.fixed(1500.0);
        check("cerca => HIGH", sel.levelFor(100.0) == LodLevel.HIGH, sel.levelFor(100.0));
        check("medio => MEDIUM", sel.levelFor(500.0) == LodLevel.MEDIUM, sel.levelFor(500.0));
        check("lejos => LOW", sel.levelFor(1200.0) == LodLevel.LOW, sel.levelFor(1200.0));
        check("fuera de rango => null", sel.levelFor(2000.0) == null, sel.levelFor(2000.0));

        boolean monotonic = true;
        LodLevel previous = LodLevel.HIGH;
        for (double dist = 0; dist <= 1500; dist += 10) {
            LodLevel level = sel.levelFor(dist);
            if (level == null) {
                break;
            }
            monotonic &= level.ordinal() >= previous.ordinal();
            previous = level;
        }
        check("detalle nunca sube con la distancia", monotonic, previous);

        // Por debajo de cierto render distance manda el piso de 256 bloques, así que la
        // proporcionalidad solo vale por encima de ese piso. Un domo de nubes minúsculo se ve peor
        // que uno generoso, y 256 bloques de nubes con 128 de terreno sigue siendo razonable.
        double rd8 = LodSelector.forRenderDistance(8, 1.5).maxDistance();
        double rd16 = LodSelector.forRenderDistance(16, 1.5).maxDistance();
        double rd32 = LodSelector.forRenderDistance(32, 1.5).maxDistance();
        check("distancia escala con render distance", rd32 > rd16 && rd16 > rd8, rd8 + " / " + rd16 + " / " + rd32);
        check("proporcional por encima del piso", Math.abs(rd32 - rd16 * 2.0) < 1e-9, rd16 + " -> " + rd32);
        check("el multiplicador se aplica",
                Math.abs(LodSelector.forRenderDistance(32, 2.0).maxDistance() - 1024.0) < 1e-9,
                LodSelector.forRenderDistance(32, 2.0).maxDistance());
        check("piso minimo con render distance bajo",
                LodSelector.forRenderDistance(2, 1.0).maxDistance() >= 256.0,
                LodSelector.forRenderDistance(2, 1.0).maxDistance());

        check("sin fade en el grueso del rango", sel.distanceFade(500.0) == 1.0F, sel.distanceFade(500.0));
        check("fade parcial cerca del borde",
                sel.distanceFade(1400.0) > 0.0F && sel.distanceFade(1400.0) < 1.0F, sel.distanceFade(1400.0));
        check("cero en el borde", sel.distanceFade(1500.0) == 0.0F, sel.distanceFade(1500.0));

        int high = LodLevel.HIGH.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        int minimal = LodLevel.MINIMAL.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        check("MINIMAL cuesta mucho menos que HIGH", minimal * 100 < high, high + " vs " + minimal);
    }

    static void fade() {
        VerticalFade f = VerticalFade.defaults();
        CloudLayerDef layer = CloudLayerDef.MID;

        check("dentro de la capa no atenua",
                f.opacity(layer.centerHeight(), layer, 0.0) == 1.0F,
                f.opacity(layer.centerHeight(), layer, 0.0));
        check("justo debajo tampoco",
                f.opacity(layer.baseHeight() - 50.0, layer, 0.0) == 1.0F,
                f.opacity(layer.baseHeight() - 50.0, layer, 0.0));
        check("muy por debajo se apaga",
                f.opacity(layer.baseHeight() - 1000.0, layer, 0.0) == 0.0F,
                f.opacity(layer.baseHeight() - 1000.0, layer, 0.0));
        check("muy por encima tambien",
                f.opacity(layer.topHeight() + 1000.0, layer, 0.0) == 0.0F,
                f.opacity(layer.topHeight() + 1000.0, layer, 0.0));

        List<Float> curve = new ArrayList<>();
        for (double y = layer.baseHeight(); y > layer.baseHeight() - 600.0; y -= 20.0) {
            curve.add(f.opacity(y, layer, 0.0));
        }
        boolean decreasing = true;
        float biggestStep = 0.0F;
        for (int i = 1; i < curve.size(); i++) {
            decreasing &= curve.get(i) <= curve.get(i - 1);
            biggestStep = Math.max(biggestStep, curve.get(i - 1) - curve.get(i));
        }
        check("la opacidad solo baja", decreasing, curve.get(curve.size() - 1));
        check("sin saltos bruscos (no es corte binario)", biggestStep < 0.2F, biggestStep);

        float looking = f.opacity(layer.baseHeight() - 300.0, layer, -45.0);
        float straight = f.opacity(layer.baseHeight() - 300.0, layer, 0.0);
        check("mirar hacia arriba la conserva", looking > straight, straight + " -> " + looking);

        check("descarte solo con opacidad casi nula",
                VerticalFade.isCulled(0.01F) && !VerticalFade.isCulled(0.05F), VerticalFade.CULL_THRESHOLD);

        try {
            new VerticalFade(100.0, 50.0);
            check("rango invalido rechazado", false, "no lanzo");
        } catch (IllegalArgumentException expected) {
            check("rango invalido rechazado", true, expected.getMessage());
        }
    }

    static void priority() {
        LodSelector sel = LodSelector.fixed(1500.0);
        check("visible y cerca es lo primero",
                RegionPriority.classify(100, sel, true, 1.0) == RegionPriority.VISIBLE_NEAR, "");
        check("visible y lejos despues",
                RegionPriority.classify(1200, sel, true, 1.0) == RegionPriority.VISIBLE_FAR, "");
        check("detras del jugador casi al final",
                RegionPriority.classify(100, sel, false, -1.0) == RegionPriority.BEHIND_PLAYER, "");
        check("fuera de camara al final",
                RegionPriority.classify(100, sel, false, 0.5) == RegionPriority.OFF_CAMERA, "");
        check("fuera de camara no se genera",
                !RegionPriority.shouldGenerate(RegionPriority.OFF_CAMERA)
                        && RegionPriority.shouldGenerate(RegionPriority.VISIBLE_NEAR), "");

        long nearFar = RegionPriority.sortKey(RegionPriority.VISIBLE_NEAR, 1400);
        long midNear = RegionPriority.sortKey(RegionPriority.VISIBLE_MID, 10);
        check("la clase pesa mas que la distancia", nearFar < midNear, nearFar + " < " + midNear);
        check("dentro de la clase gana la cercana",
                RegionPriority.sortKey(RegionPriority.VISIBLE_NEAR, 10)
                        < RegionPriority.sortKey(RegionPriority.VISIBLE_NEAR, 900), "");
        check("distancia negativa no rompe la clave",
                RegionPriority.sortKey(RegionPriority.VISIBLE_NEAR, -5) >= 0, "");
    }

    static void budget() {
        CloudBudget budget = new CloudBudget(2, 1000, 64);
        budget.beginFrame();
        check("presupuesto inicial permite generar", budget.canGenerate(400), "");
        budget.recordGenerated(400);
        budget.recordGenerated(400);
        check("corta al llegar al maximo de regiones", !budget.canGenerate(100), budget.regionsThisFrame());
        budget.beginFrame();
        check("el frame siguiente arranca limpio", budget.canGenerate(400) && budget.quadsThisFrame() == 0, "");
        check("corta por cuadruples tambien", !budget.canGenerate(5000), "");
        check("siempre entra al menos una region", budget.canGenerateAtLeastOne(), "");
        budget.recordGenerated(10);
        check("pero solo la primera", !budget.canGenerateAtLeastOne(), "");
    }

    static void regions() {
        RegionKey a = RegionKey.of(0, 10.0, 20.0);
        check("origen cae en la region 0,0", a.x() == 0 && a.z() == 0, a);
        RegionKey b = RegionKey.of(0, -1.0, -1.0);
        check("negativos van a la region -1,-1", b.x() == -1 && b.z() == -1, b);
        check("el borde pertenece a la region siguiente",
                RegionKey.of(0, RegionKey.REGION_SIZE, 0).x() == 1, RegionKey.of(0, RegionKey.REGION_SIZE, 0));
        check("centro dentro de la region",
                a.centerX() > a.originX() && a.centerX() < a.originX() + RegionKey.REGION_SIZE, a.centerX());
        check("igualdad por valor (sirve de clave de cache)",
                RegionKey.of(1, 5, 5).equals(RegionKey.of(1, 5, 5))
                        && !RegionKey.of(1, 5, 5).equals(RegionKey.of(2, 5, 5)), "");

        boolean exact = true;
        for (LodLevel level : LodLevel.values()) {
            exact &= RegionKey.REGION_SIZE % level.cellSize() == 0;
        }
        check("todos los LOD dividen la region exacto", exact, RegionKey.REGION_SIZE);

        CloudLayerDef layer = CloudLayerDef.HIGH;
        check("el viento avanza con el tiempo",
                layer.windOffsetX(10.0) > layer.windOffsetX(0.0), layer.windOffsetX(10.0));
        check("capas con velocidades distintas",
                CloudLayerDef.HIGH.speedX() != CloudLayerDef.LOW.speedX(),
                CloudLayerDef.HIGH.speedX() + " vs " + CloudLayerDef.LOW.speedX());
        check("capas ordenadas en altura",
                CloudLayerDef.LOW.centerHeight() < CloudLayerDef.MID.centerHeight()
                        && CloudLayerDef.MID.centerHeight() < CloudLayerDef.HIGH.centerHeight(), "");
        check("capas sin superposicion vertical",
                CloudLayerDef.LOW.topHeight() < CloudLayerDef.MID.baseHeight()
                        && CloudLayerDef.MID.topHeight() < CloudLayerDef.HIGH.baseHeight(), "");
    }
}
