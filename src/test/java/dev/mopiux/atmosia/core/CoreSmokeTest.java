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
        profiles();
        coverage();
        modes();
        stackOpacity();
        prefetch();

        System.out.println(fails == 0 ? "\nTODO OK" : "\n" + fails + " FALLAS");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** Perfiles gráficos: que los tres se ordenen y que el tope de detalle mande. */
    static void profiles() {
        QualityProfile.Settings low = QualityProfile.LOW.resolve(null);
        QualityProfile.Settings mid = QualityProfile.MEDIUM.resolve(null);
        QualityProfile.Settings high = QualityProfile.HIGH.resolve(null);

        check("el domo crece de Bajo a Alto",
                low.distanceMultiplier() < mid.distanceMultiplier()
                        && mid.distanceMultiplier() < high.distanceMultiplier(),
                low.distanceMultiplier() + " < " + mid.distanceMultiplier() + " < " + high.distanceMultiplier());
        check("el presupuesto crece de Bajo a Alto",
                low.quadsPerFrame() < mid.quadsPerFrame() && mid.quadsPerFrame() < high.quadsPerFrame(), "");
        check("la cache crece de Bajo a Alto",
                low.maxCachedRegions() < mid.maxCachedRegions()
                        && mid.maxCachedRegions() < high.maxCachedRegions(), "");
        check("Bajo recorta cortes por capa",
                low.detailCap().slices() < mid.detailCap().slices(),
                low.detailCap().slices() + " vs " + mid.detailCap().slices());
        check("ningun perfil agranda la celda",
                low.detailCap().cellSize() == mid.detailCap().cellSize(), low.detailCap().cellSize());

        QualityProfile.Settings fromFile =
                new QualityProfile.Settings(7.0D, LodLevel.LOW, 9, 99_999, 77);
        check("CUSTOM deja mandar al archivo",
                QualityProfile.CUSTOM.resolve(fromFile) == fromFile, "");
        check("los perfiles fijos ignoran el archivo",
                QualityProfile.MEDIUM.resolve(fromFile) != fromFile, "");
        check("solo CUSTOM usa el archivo",
                QualityProfile.CUSTOM.usesConfigValues() && !QualityProfile.LOW.usesConfigValues(), "");

        // El tope de detalle es lo que convierte un perfil en ahorro real de relleno.
        LodSelector capped = LodSelector.fixed(1000.0D, LodLevel.MEDIUM);
        LodSelector open = LodSelector.fixed(1000.0D, LodLevel.HIGH);
        check("el tope recorta el nivel mas fino",
                capped.levelFor(10.0D) == LodLevel.MEDIUM && open.levelFor(10.0D) == LodLevel.HIGH,
                capped.levelFor(10.0D));
        check("el tope no sube el nivel a lo lejos",
                capped.levelFor(999.0D) == open.levelFor(999.0D), capped.levelFor(999.0D));
        check("fuera de rango sigue siendo nulo con tope",
                capped.levelFor(1001.0D) == null, capped.levelFor(1001.0D));
        check("sin tope explicito no se recorta nada",
                LodSelector.fixed(1000.0D).levelFor(10.0D) == LodLevel.HIGH, "");

        // Habia un cuarto nivel con un tramo de ancho cero: no se usaba nunca.
        LodSelector escala = LodSelector.forRenderDistance(16, 3.0D, LodLevel.HIGH);
        java.util.Set<LodLevel> vistos = new java.util.LinkedHashSet<>();
        for (double d = 0.0D; d <= escala.maxDistance(); d += 1.0D) {
            LodLevel l = escala.levelFor(d);
            if (l != null) {
                vistos.add(l);
            }
        }
        check("todos los niveles de LOD son alcanzables",
                vistos.size() == LodLevel.values().length, vistos + " de " + LodLevel.values().length);
    }

    /** Cantidad de nubes: que el multiplicador llegue a la densidad y no se desborde. */
    static void coverage() {
        NoiseField noise = new NoiseField(99L);
        CloudLayerDef layer = CloudLayerDef.MID;

        DensityField plain = new DensityField(noise, layer);
        DensityField more = new DensityField(noise, layer, 1.6D);
        DensityField less = new DensityField(noise, layer, 0.4D);

        check("sin multiplicador la capa queda como fue disenada",
                plain.effectiveCoverage() == layer.coverage(), plain.effectiveCoverage());
        check("mas cantidad es mas cobertura",
                more.effectiveCoverage() > plain.effectiveCoverage()
                        && plain.effectiveCoverage() > less.effectiveCoverage(),
                more.effectiveCoverage() + " > " + plain.effectiveCoverage()
                        + " > " + less.effectiveCoverage());
        check("la cobertura nunca llega a tapar el cielo entero",
                new DensityField(noise, layer, 100.0D).effectiveCoverage() < 1.0D,
                new DensityField(noise, layer, 100.0D).effectiveCoverage());
        check("la cobertura nunca se va abajo de cero",
                new DensityField(noise, layer, 0.0D).effectiveCoverage() == 0.0D, "");

        // Lo que de verdad importa: que a igual punto del cielo, mas cantidad no quite nube.
        int denser = 0;
        int sparser = 0;
        for (int i = 0; i < 400; i++) {
            double x = i * 137.0D;
            double z = i * 311.0D;
            double a = plain.densityAt(x, z);
            double b = more.densityAt(x, z);
            if (b > a) {
                denser++;
            }
            if (b < a) {
                sparser++;
            }
        }
        check("subir la cantidad nunca quita nube en un punto", sparser == 0, sparser + " puntos");
        check("y en muchos puntos agrega", denser > 40, denser + " de 400");

        check("con cobertura cero el cielo queda despejado",
                new DensityField(noise, layer, 0.0D).densityAt(500.0D, 700.0D) == 0.0D, "");
    }

    /** Los tres modos y lo que cada uno implica. */
    static void modes() {
        check("solo Atmosia dibuja",
                CloudMode.ATMOSIA.drawsAtmosia() && !CloudMode.VANILLA.drawsAtmosia()
                        && !CloudMode.NONE.drawsAtmosia(), "");
        check("vanilla es el unico modo que no suprime",
                !CloudMode.VANILLA.suppressesVanilla() && CloudMode.ATMOSIA.suppressesVanilla()
                        && CloudMode.NONE.suppressesVanilla(), "");
        check("NINGUNA suprime sin dibujar (es el modo de diagnostico)",
                CloudMode.NONE.suppressesVanilla() && !CloudMode.NONE.drawsAtmosia(), "");

        boolean named = true;
        for (CloudMode m : CloudMode.values()) {
            named &= !m.displayName().isBlank() && !m.description().isBlank();
        }
        check("todos los modos tienen nombre y explicacion", named, "");

        boolean describedProfiles = true;
        for (QualityProfile p : QualityProfile.values()) {
            describedProfiles &= !p.displayName().isBlank() && !p.description().isBlank();
        }
        check("todos los perfiles tienen nombre y explicacion", describedProfiles, "");
    }

    /** Opacidad de la pila: constante entre niveles, y nunca del todo opaca. */
    static void stackOpacity() {
        double objetivo = DensityField.stackOpacity();
        check("la pila no llega a tapar el cielo", objetivo < 1.0D && objetivo > 0.5D, objetivo);

        double peor = 0.0D;
        for (LodLevel level : LodLevel.values()) {
            float alfa = DensityField.sliceAlpha(level.slices());
            double resto = 1.0D;
            for (int i = 0; i < level.slices(); i++) {
                resto *= (1.0D - alfa);
            }
            double acumulada = 1.0D - resto;
            peor = Math.max(peor, Math.abs(acumulada - objetivo));
            check("  " + level + " (" + level.slices() + " cortes) llega al objetivo",
                    Math.abs(acumulada - objetivo) < 1.0E-6D, acumulada);
        }
        // Es el defecto que se veia volando: cada cambio de nivel cambiaba el brillo de la nube.
        check("ningun cambio de nivel altera la opacidad", peor < 1.0E-6D, peor);

        check("mas cortes, menos alfa cada uno",
                DensityField.sliceAlpha(8) < DensityField.sliceAlpha(4)
                        && DensityField.sliceAlpha(4) < DensityField.sliceAlpha(2), "");
        check("un solo corte aporta toda la opacidad",
                Math.abs(DensityField.sliceAlpha(1) - objetivo) < 1.0E-6D, DensityField.sliceAlpha(1));
        check("cero cortes no rompe", DensityField.sliceAlpha(0) > 0.0F, DensityField.sliceAlpha(0));

        // El compuesto visto desde abajo nunca debe caer por debajo del cielo: eso era la banda gris.
        NoiseField ruido = new NoiseField(7L);
        DensityField campo = new DensityField(ruido, CloudLayerDef.LOW);
        double[] cielo = { 0.620D, 0.710D, 0.850D };
        float[] color = { CloudLayerDef.LOW.red(), CloudLayerDef.LOW.green(), CloudLayerDef.LOW.blue() };
        int cortes = LodLevel.HIGH.slices();
        float alfa = DensityField.sliceAlpha(cortes);
        double[] c = { cielo[0], cielo[1], cielo[2] };
        double masOscuro = 1.0D;
        for (int i = 0; i < cortes; i++) {
            float sombra = campo.shade(1.0D, i, cortes);
            for (int k = 0; k < 3; k++) {
                c[k] = color[k] * sombra * alfa + c[k] * (1.0D - alfa);
            }
            masOscuro = Math.min(masOscuro, (c[0] + c[1] + c[2]) / 3.0D);
        }
        double brilloCielo = (cielo[0] + cielo[1] + cielo[2]) / 3.0D;
        double caida = brilloCielo - masOscuro;

        // Lo mismo con las constantes de la 0.2.1, para medir la mejora en vez de afirmarla.
        double[] viejo = { cielo[0], cielo[1], cielo[2] };
        double peorViejo = 1.0D;
        for (int i = 0; i < cortes; i++) {
            double sombra = (0.62D + (1.0D - 0.62D) * ((double) i / (cortes - 1))) * (1.0D - 0.12D);
            for (int k = 0; k < 3; k++) {
                viejo[k] = color[k] * sombra * 0.55D + viejo[k] * (1.0D - 0.55D);
            }
            peorViejo = Math.min(peorViejo, (viejo[0] + viejo[1] + viejo[2]) / 3.0D);
        }
        double caidaVieja = brilloCielo - peorViejo;

        // Una nube vista desde abajo tiene que ser algo mas oscura que el cielo: si no, se lee como
        // niebla. Lo que era defecto es la magnitud, que producia una banda gris marcada.
        check("queda algo mas oscura que el cielo, como corresponde", caida > 0.0D,
                String.format(Locale.ROOT, "%.3f", caida));
        check("pero la caida es chica", caida < 0.08D, String.format(Locale.ROOT, "%.3f", caida));
        check("y menos de la mitad que en la 0.2.1", caida < caidaVieja / 2.0D,
                String.format(Locale.ROOT, "%.3f vs %.3f antes", caida, caidaVieja));
    }

    /** Prefetch direccional: que anticipe volando y que no haga nada caminando. */
    static void prefetch() {
        MotionPrefetch p = new MotionPrefetch();
        // Caminar: 4,3 bloques por segundo.
        double t = 0.0D;
        double x = 0.0D;
        for (int i = 0; i < 120; i++) {
            t += 1.0D / 20.0D;
            x += 4.3D / 20.0D;
            p.update(x, 0.0D, t);
        }
        check("caminando no adelanta nada", p.leadLength() == 0.0D, p.leadLength());

        // Elytra: 45 bloques por segundo en diagonal.
        MotionPrefetch v = new MotionPrefetch();
        t = 0.0D;
        x = 0.0D;
        double z = 0.0D;
        double vx = 45.0D / Math.sqrt(2.0D);
        for (int i = 0; i < 400; i++) {
            t += 1.0D / 20.0D;
            x += vx / 20.0D;
            z += vx / 20.0D;
            v.update(x, z, t);
        }
        check("volando estima la velocidad", Math.abs(v.speed() - 45.0D) < 1.0D, v.speed());
        check("volando adelanta lo que corresponde",
                Math.abs(v.leadLength() - 45.0D * MotionPrefetch.HORIZON_SECONDS) < 2.0D, v.leadLength());
        check("el adelanto mantiene la direccion",
                Math.abs(v.leadX() - v.leadZ()) < 1.0E-6D, v.leadX() + " / " + v.leadZ());

        // Teletransporte: un salto enorme en un frame no debe disparar el adelanto.
        MotionPrefetch tp = new MotionPrefetch();
        tp.update(0.0D, 0.0D, 0.0D);
        tp.update(1.0E7D, 1.0E7D, 0.05D);
        check("un teletransporte no adelanta nada", tp.leadLength() == 0.0D, tp.leadLength());

        // Velocidad alta pero creible: el tope manda, sin torcer la direccion.
        MotionPrefetch rapido = new MotionPrefetch();
        t = 0.0D;
        x = 0.0D;
        for (int i = 0; i < 400; i++) {
            t += 1.0D / 20.0D;
            x += 190.0D / 20.0D;
            rapido.update(x, 0.0D, t);
        }
        check("a 190 b/s el tope se alcanza de verdad (no es codigo muerto)",
                190.0D * MotionPrefetch.HORIZON_SECONDS > MotionPrefetch.MAX_LEAD, "");
        check("el adelanto esta acotado",
                Math.abs(rapido.leadLength() - MotionPrefetch.MAX_LEAD) < 1.0E-6D, rapido.leadLength());
        check("y sigue apuntando a donde va", rapido.leadX() > 0.0D && rapido.leadZ() == 0.0D, "");

        // Una pausa no se lee como movimiento.
        MotionPrefetch pausa = new MotionPrefetch();
        pausa.update(0.0D, 0.0D, 0.0D);
        pausa.update(500.0D, 0.0D, 30.0D);
        check("una pausa larga no deja velocidad residual", pausa.speed() == 0.0D, pausa.speed());

        check("reset deja todo en cero", resetLimpio(), "");
    }

    static boolean resetLimpio() {
        MotionPrefetch p = new MotionPrefetch();
        double t = 0.0D;
        double x = 0.0D;
        for (int i = 0; i < 200; i++) {
            t += 1.0D / 20.0D;
            x += 45.0D / 20.0D;
            p.update(x, 0.0D, t);
        }
        p.reset();
        return p.speed() == 0.0D && p.leadLength() == 0.0D;
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

        // Por debajo de cierto render distance manda el piso, así que la proporcionalidad solo vale
        // por encima de él. El piso es generoso a propósito: la primera prueba real mostró que un
        // domo corto se ve recortado dentro del campo de visión, y eso se nota mucho más que el
        // costo de unas regiones de más.
        check("piso de 512 con render distance bajo",
                Math.abs(LodSelector.forRenderDistance(4, 1.5).maxDistance() - 512.0) < 1e-9,
                LodSelector.forRenderDistance(4, 1.5).maxDistance());

        double rd24 = LodSelector.forRenderDistance(24, 1.5).maxDistance();
        double rd48 = LodSelector.forRenderDistance(48, 1.5).maxDistance();
        check("distancia escala con render distance", rd48 > rd24, rd24 + " -> " + rd48);
        check("proporcional por encima del piso", Math.abs(rd48 - rd24 * 2.0) < 1e-9, rd24 + " -> " + rd48);
        check("el multiplicador se aplica",
                Math.abs(LodSelector.forRenderDistance(32, 3.0).maxDistance() - 1536.0) < 1e-9,
                LodSelector.forRenderDistance(32, 3.0).maxDistance());

        // Con el domo por defecto, ninguna celda llega a verse como una sábana en el cielo.
        int celdaMasGrande = 0;
        for (LodLevel l : LodLevel.values()) {
            celdaMasGrande = Math.max(celdaMasGrande, l.cellSize());
        }
        check("celda maxima acotada", celdaMasGrande <= 32, celdaMasGrande);
        check("piso minimo con render distance bajo",
                LodSelector.forRenderDistance(2, 1.0).maxDistance() >= 256.0,
                LodSelector.forRenderDistance(2, 1.0).maxDistance());

        check("sin fade en el grueso del rango", sel.distanceFade(500.0) == 1.0F, sel.distanceFade(500.0));
        check("fade parcial cerca del borde",
                sel.distanceFade(1400.0) > 0.0F && sel.distanceFade(1400.0) < 1.0F, sel.distanceFade(1400.0));
        check("cero en el borde", sel.distanceFade(1500.0) == 0.0F, sel.distanceFade(1500.0));

        int high = LodLevel.HIGH.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        int barato = LodLevel.LOW.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        check("el nivel mas barato cuesta mucho menos que HIGH", barato * 8 <= high, high + " vs " + barato);
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
