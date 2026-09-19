package dev.mopiux.atmosia.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comprobaciones del nucleo procedural: ruido, densidad, LOD, fade vertical, prioridad y
 * presupuesto. Nada de esto depende de Minecraft, asi que se puede verificar de verdad.
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
        seams();
        terrazas();
        ordenDeMezcla();
        bultos();

        System.out.println(fails == 0 ? "\nTODO OK" : "\n" + fails + " FALLAS");
        System.exit(fails == 0 ? 0 : 1);
    }

    /**
     * Que la pila no se lea como una escalera de terrazas vista de canto.
     *
     * Es el defecto que quedaba tras cerrar la costura de LOD: cada corte terminaba su silueta
     * justo donde empezaba la del siguiente, sin solapamiento, y desde abajo en angulo rasante se
     * veia un escalon por corte. La invariante que lo evita es que la transicion de borde sea
     * bastante mas ancha que el salto de umbral entre cortes vecinos.
     */
    static void terrazas() {
        NoiseField n = new NoiseField(11L);
        double borde = DensityField.edgeSoftness();

        for (CloudLayerDef capa : CloudLayerDef.DEFAULTS) {
            DensityField f = new DensityField(n, capa);
            for (LodLevel nivel : LodLevel.values()) {
                int cortes = nivel.slices();
                if (cortes < 2) {
                    continue;
                }
                double salto = f.maxThresholdGap(cortes);
                check("  " + capa.name() + "/" + nivel + ": las siluetas vecinas se solapan",
                        borde > salto * 2.0D,
                        String.format(Locale.ROOT, "borde %.3f vs salto %.3f", borde, salto));
            }
        }

        DensityField f = new DensityField(n, CloudLayerDef.LOW);

        // El perfil vertical tiene que seguir existiendo: si todos los umbrales fueran iguales la
        // capa seria una losa y los cortes no aportarian ninguna forma.
        double centro = f.sliceThreshold(4, 8);
        double extremo = f.sliceThreshold(0, 8);
        check("el perfil vertical sobrevive al aplanado",
                extremo > centro * 3.0D,
                String.format(Locale.ROOT, "extremo %.3f vs centro %.3f", extremo, centro));

        // Y el umbral tiene que seguir siendo funcion de la altura, no del indice: es lo que
        // mantiene cerradas las costuras entre niveles de detalle.
        check("el umbral sigue dependiendo solo de la altura",
                Math.abs(f.thresholdAt(DensityField.sliceT(1, 4)) - f.sliceThreshold(1, 4)) < 1.0E-9D,
                "");

        // Una celda cuyas cuatro esquinas estan en cero no aporta geometria; una con una sola
        // esquina con densidad si, y por eso la silueta deja de ser escalonada.
        check("el borde reparte alfa parcial",
                f.cellAlpha(centro + borde * 0.5D, centro) > 0.4F
                        && f.cellAlpha(centro + borde * 0.5D, centro) < 0.6F,
                f.cellAlpha(centro + borde * 0.5D, centro));
        check("el borde satura recien al final del rango",
                f.cellAlpha(centro + borde * 0.99D, centro) < 1.0F
                        && f.cellAlpha(centro + borde * 1.01D, centro) == 1.0F, "");

        // La prueba de verdad: un rayo rasante que atraviesa la pila no debe encontrar ningun
        // escalon visible. Reproduce lo que se midio en las capturas -grupos de escalones de 2 a
        // 11 niveles de gris, separados unos pocos pixeles- y comprueba que ya no aparezcan.
        // Se mide la CURVATURA y no el salto a secas: un degradado suave tambien cambia de un
        // pixel al siguiente, y lo que delata un escalon es que ese cambio se quiebre. Es el mismo
        // criterio con el que se midieron las capturas.
        int muestras = 300;
        double peorActual = 0.0D;
        double peorViejo = 0.0D;
        double sumaActual = 0.0D;
        double sumaVieja = 0.0D;
        for (double az = 0.0D; az < 6.2D; az += 0.31D) {
            double[] actual = new double[muestras];
            double[] antes = new double[muestras];
            for (int i = 0; i < muestras; i++) {
                double elevacion = 4.0D + 18.0D * i / (muestras - 1.0D);
                actual[i] = columnaRasante(f, n, CloudLayerDef.LOW, elevacion, az, false);
                antes[i] = columnaRasante(f, n, CloudLayerDef.LOW, elevacion, az, true);
            }
            for (int i = 1; i + 1 < muestras; i++) {
                double ca = Math.abs(actual[i + 1] - 2 * actual[i] + actual[i - 1]) * 255.0D;
                double cv = Math.abs(antes[i + 1] - 2 * antes[i] + antes[i - 1]) * 255.0D;
                peorActual = Math.max(peorActual, ca);
                peorViejo = Math.max(peorViejo, cv);
                sumaActual += ca;
                sumaVieja += cv;
            }
        }
        // El criterio es absoluto y no un multiplo: un framebuffer de ocho bits por canal no puede
        // representar una diferencia menor a un nivel de gris, asi que un quiebre por debajo de uno
        // no se puede dibujar aunque el calculo lo produzca.
        check("ningun quiebre de la columna llega a un nivel de gris",
                peorActual < 1.0D,
                String.format(Locale.ROOT, "%.2f niveles, contra %.2f de la 0.2.4",
                        peorActual, peorViejo));
        check("y ademas queda muy por debajo del de la 0.2.4",
                peorActual * 2.5D < peorViejo,
                String.format(Locale.ROOT, "%.2f contra %.2f", peorActual, peorViejo));
        check("la energia de escalones de la columna baja al menos cinco veces",
                sumaActual * 5.0D < sumaVieja,
                String.format(Locale.ROOT, "%.1f contra %.1f", sumaActual, sumaVieja));
    }

    /**
     * Brillo compuesto de un rayo que sale del ojo con una elevacion y un azimut dados y atraviesa
     * los ocho cortes de una capa.
     *
     * Modela lo que hace la GPU: la densidad se toma en las cuatro esquinas de la celda y el alfa
     * se interpola por la cara. Es la diferencia con la 0.2.4, donde la densidad era el centro de
     * la celda y el alfa quedaba plano en todo el cuadrilatero -y cada vez que el punto de cruce
     * pasaba de una celda a la vecina, el alfa saltaba de golpe.
     */
    static double columnaRasante(DensityField f, NoiseField n, CloudLayerDef capa,
                                 double elevacionGrados, double azimut, boolean comoLa024) {
        final double ojo = 100.0D;
        final double celda = 16.0D;
        int cortes = 8;
        float[] alfas = DensityField.sliceAlphas(cortes);
        double alfaPlano = 1.0D - Math.pow(1.0D - DensityField.stackOpacity(), 1.0D / cortes);
        double color = 0.72D;

        for (int i = cortes - 1; i >= 0; i--) {   // de lejos a cerca: el corte mas alto primero
            double t = DensityField.sliceT(i, cortes);
            double altura = capa.baseHeight() + t * capa.thickness();
            double radio = (altura - ojo) / Math.tan(Math.toRadians(elevacionGrados));
            double x = radio * Math.cos(azimut);
            double z = radio * Math.sin(azimut);

            double alfa;
            double propio;
            if (comoLa024) {
                // Densidad del centro de la celda, plana en todo el cuadrilatero, y la curva de
                // umbral y el borde de la 0.2.4. Cada vez que el punto de cruce pasaba a la celda
                // vecina, el alfa saltaba de golpe: eso es el escalon.
                double cx = (Math.floor(x / celda) + 0.5D) * celda;
                double cz = (Math.floor(z / celda) + 0.5D) * celda;
                double d = f.densityAt(cx, cz);
                double desdeCentro = Math.abs(t - 0.5D) * 2.0D;
                double umbral = 0.06D + 0.62D * Math.pow(desdeCentro, 1.6D);
                alfa = d <= umbral ? 0.0D : Math.min(1.0D, (d - umbral) / 0.22D);
                propio = capa.green() * (0.78D + 0.22D * t) * (1.0D - 0.12D * d);
                alfa *= alfaPlano;
            } else {
                // Densidad en las cuatro esquinas, alfa y sombra interpolados por la cara: es lo
                // que hace la GPU con un color por vertice.
                double umbral = f.sliceThreshold(i, cortes);
                double x0 = Math.floor(x / celda) * celda;
                double z0 = Math.floor(z / celda) * celda;
                double fx = (x - x0) / celda;
                double fz = (z - z0) / celda;
                double suma = 0.0D;
                propio = 0.0D;
                for (int esquina = 0; esquina < 4; esquina++) {
                    double ex = x0 + (esquina % 2) * celda;
                    double ez = z0 + (esquina / 2) * celda;
                    double peso = ((esquina % 2) == 0 ? 1.0D - fx : fx)
                            * ((esquina / 2) == 0 ? 1.0D - fz : fz);
                    double d = f.densityAt(ex, ez);
                    suma += peso * f.cellAlpha(d, umbral);
                    propio += peso * capa.green() * f.shade(d, i, cortes);
                }
                alfa = suma * alfas[i];
            }
            color = propio * alfa + color * (1.0D - alfa);
        }
        return color;
    }

    /**
     * Que el borde entre dos regiones no se vea, aunque cada region sea un draw call propio.
     *
     * Cada region se mezcla entera de una vez. Un rayo rasante cerca del borde cruza algunos cortes
     * de una region y algunos de la vecina, asi que el orden efectivo de mezcla cambia al cruzar el
     * borde. Si el orden interno de cada region no coincide con el orden de profundidad global, ese
     * cambio produce un salto de color en una linea recta de 256 bloques: la grilla en el cielo.
     *
     * Mezclar de lejos a cerca -los cortes altos primero, mirando desde abajo- hace que el reparto
     * entre dos regiones de exactamente lo mismo que la pila entera.
     */
    static void ordenDeMezcla() {
        NoiseField n = new NoiseField(5L);
        DensityField f = new DensityField(n, CloudLayerDef.LOW);
        int cortes = LodLevel.HIGH.slices();

        double peorCorrecto = 0.0D;
        double peorInvertido = 0.0D;
        for (int paso = 0; paso <= 100; paso++) {
            double d = paso / 100.0D;

            // Orden correcto: de arriba hacia abajo, y la region lejana (los cortes altos) entera
            // antes que la cercana. El reparto tiene que dar lo mismo que la pila completa.
            int[] entera = new int[cortes];
            int[] partida = new int[cortes];
            for (int i = 0; i < cortes; i++) {
                entera[i] = cortes - 1 - i;
                partida[i] = cortes - 1 - i;
            }
            peorCorrecto = Math.max(peorCorrecto,
                    Math.abs(mezcla(f, CloudLayerDef.LOW, cortes, d, entera)
                            - mezcla(f, CloudLayerDef.LOW, cortes, d, partida)) * 255.0D);

            // Orden de la 0.2.6: de abajo hacia arriba dentro de cada region, pero la region lejana
            // primero. Es el que producia la grilla.
            int[] viejoEntero = new int[cortes];
            int[] viejoPartido = new int[cortes];
            for (int i = 0; i < cortes; i++) {
                viejoEntero[i] = i;
            }
            int k = 0;
            for (int i = cortes / 2; i < cortes; i++) {
                viejoPartido[k++] = i;
            }
            for (int i = 0; i < cortes / 2; i++) {
                viejoPartido[k++] = i;
            }
            peorInvertido = Math.max(peorInvertido,
                    Math.abs(mezcla(f, CloudLayerDef.LOW, cortes, d, viejoEntero)
                            - mezcla(f, CloudLayerDef.LOW, cortes, d, viejoPartido)) * 255.0D);
        }

        check("el borde entre regiones da exactamente el mismo color",
                peorCorrecto < 0.001D,
                String.format(Locale.ROOT, "%.4f niveles de gris", peorCorrecto));
        check("  y el orden anterior si producia un salto grande",
                peorInvertido > 5.0D,
                String.format(Locale.ROOT, "%.2f niveles de gris", peorInvertido));
    }

    /** Compone los cortes en el orden dado. */
    static double mezcla(DensityField f, CloudLayerDef capa, int cortes, double densidad,
                         int[] orden) {
        float[] alfas = DensityField.sliceAlphas(cortes);
        double propio = 0.0D;
        double resto = 1.0D;
        for (int i : orden) {
            double a = f.cellAlpha(densidad, f.sliceThreshold(i, cortes)) * alfas[i];
            propio = capa.green() * f.shade(densidad, i, cortes) * a + propio * (1.0D - a);
            resto *= (1.0D - a);
        }
        return propio + 0.72D * resto;
    }

    /**
     * Color final de una columna de nube sobre el cielo, con {@code cortes} cortes.
     *
     * Se compone en el orden en que el constructor emite los cortes, que es el orden en que la GPU
     * los mezcla.
     */
    static double columnaCompuesta(DensityField f, CloudLayerDef capa, int cortes, double densidad) {
        return columnaCompuesta(f, capa, cortes, densidad, true);
    }

    /** Igual que en el constructor de mallas: los dos factores de igualacion y el orden de mezcla. */
    static double columnaCompuesta(DensityField f, CloudLayerDef capa, int cortes, double densidad,
                                   boolean topDown) {
        float[] alfas = DensityField.sliceAlphas(cortes);
        float k = DensityField.alphaScale(cortes, densidad, topDown);
        float tope = DensityField.maxSliceAlpha();
        double propio = 0.0D;
        double resto = 1.0D;
        for (int paso = 0; paso < cortes; paso++) {
            int i = topDown ? cortes - 1 - paso : paso;
            double a = Math.min(tope,
                    f.cellAlpha(densidad, f.sliceThreshold(i, cortes)) * alfas[i] * k);
            propio = capa.green() * f.shade(densidad, i, cortes, topDown) * a + propio * (1.0D - a);
            resto *= (1.0D - a);
        }
        return propio + 0.72D * resto;
    }

    /**
     * La siembra de bultos de la tecnica SPRITES.
     *
     * Lo que importa verificar aca no es como se ve -eso no se puede medir sin dibujar- sino que la
     * siembra sea determinista y que no haya ninguna estructura alineada con la grilla: si los
     * bultos cayeran exactamente en una grilla, volveriamos a tener rectas, que es justamente lo
     * que esta tecnica existe para evitar.
     */
    static void bultos() {
        NoiseField n = new NoiseField(21L);
        DensityField f = new DensityField(n, CloudLayerDef.LOW, 1.5D);

        java.util.List<PuffField.Puff> a = new java.util.ArrayList<>();
        java.util.List<PuffField.Puff> b = new java.util.ArrayList<>();
        PuffField.seed(f, CloudLayerDef.LOW, 0.0D, 0.0D, RegionKey.REGION_SIZE, 21L, a::add);
        PuffField.seed(f, CloudLayerDef.LOW, 0.0D, 0.0D, RegionKey.REGION_SIZE, 21L, b::add);

        check("la siembra es determinista", a.size() == b.size() && !a.isEmpty(),
                a.size() + " vs " + b.size());
        boolean iguales = true;
        for (int i = 0; i < a.size(); i++) {
            iguales &= a.get(i).equals(b.get(i));
        }
        check("  y bulto por bulto", iguales, "");

        // Ningun bulto puede quedar fuera de su capa: si sobresaliera, el borde de la capa se
        // veria como un corte.
        boolean dentro = true;
        boolean alfaValido = true;
        for (PuffField.Puff p : a) {
            dentro &= p.y() >= CloudLayerDef.LOW.baseHeight() - 0.01F
                    && p.y() <= CloudLayerDef.LOW.topHeight() + 0.01F;
            alfaValido &= p.alpha() > 0.0F && p.alpha() <= 0.51F && p.radius() > 5.0F;
        }
        check("todos los bultos quedan dentro de su capa", dentro, "");
        check("alfa y radio en rango", alfaValido, "");

        // La prueba que de verdad importa: las posiciones NO pueden estar alineadas con la grilla
        // de siembra. Si lo estuvieran, las columnas de bultos formarian rectas en perspectiva.
        // Se mide el histograma de la posicion DENTRO de la celda de siembra. Si la siembra
        // estuviera alineada, casi todos caerian en la misma cubeta. Repartido quiere decir que
        // ninguna cubeta se lleva mucho mas que su parte.
        int cubetas = PuffField.SEED_SPACING;
        int[] hist = new int[cubetas];
        java.util.List<PuffField.Puff> amplia = new java.util.ArrayList<>();
        for (int rz = 0; rz < 3; rz++) {
            for (int rx = 0; rx < 3; rx++) {
                PuffField.seed(f, CloudLayerDef.LOW, rx * 256.0D, rz * 256.0D,
                        RegionKey.REGION_SIZE, 21L, amplia::add);
            }
        }
        for (PuffField.Puff p : amplia) {
            int bin = (int) Math.floor(((p.x() % cubetas) + cubetas) % cubetas);
            hist[Math.max(0, Math.min(cubetas - 1, bin))]++;
        }
        int mayor = 0;
        for (int v : hist) {
            mayor = Math.max(mayor, v);
        }
        double media = amplia.size() / (double) cubetas;
        check("los bultos no se alinean con la grilla de siembra",
                mayor < media * 2.5D,
                String.format(Locale.ROOT, "cubeta mas cargada %d, media %.1f, sobre %d bultos",
                        mayor, media, amplia.size()));

        // El desorden tiene que estar repartido, no concentrado a un lado.
        double suma = 0.0;
        double peor = 0.0;
        int muestras = 4000;
        for (int i = 0; i < muestras; i++) {
            double j = PuffField.jitter(i, i * 7L, i % 8, 21L);
            suma += j;
            peor = Math.max(peor, Math.abs(j));
        }
        check("el desorden esta centrado", Math.abs(suma / muestras) < 0.02D, suma / muestras);
        check("y acotado a media celda", peor <= 0.5D, peor);

        // El perfil vertical se anula en los extremos: es lo que afina la capa arriba y abajo.
        check("el perfil vertical se anula en los bordes",
                PuffField.verticalProfile(0.0D) < 1.0E-9D
                        && PuffField.verticalProfile(1.0D) < 1.0E-9D
                        && Math.abs(PuffField.verticalProfile(0.5D) - 1.0D) < 1.0E-9D, "");

        // Mas densidad, mas bultos apilados: es lo que da el volumen.
        check("la pila crece con la densidad",
                PuffField.stackFor(0.05D) == 0
                        && PuffField.stackFor(0.2D) >= 1
                        && PuffField.stackFor(0.9D) > PuffField.stackFor(0.2D),
                PuffField.stackFor(0.2D) + " -> " + PuffField.stackFor(0.9D));

        // Dos regiones vecinas: la siembra no puede dejar un hueco ni un amontonamiento en el
        // borde entre ellas, porque eso volveria a ser una recta.
        java.util.List<PuffField.Puff> izq = new java.util.ArrayList<>();
        java.util.List<PuffField.Puff> der = new java.util.ArrayList<>();
        PuffField.seed(f, CloudLayerDef.LOW, 0.0D, 0.0D, RegionKey.REGION_SIZE, 21L, izq::add);
        PuffField.seed(f, CloudLayerDef.LOW, RegionKey.REGION_SIZE, 0.0D, RegionKey.REGION_SIZE,
                21L, der::add);
        int cercaIzq = 0;
        int cercaDer = 0;
        for (PuffField.Puff p : izq) {
            if (p.x() > RegionKey.REGION_SIZE - PuffField.SEED_SPACING) {
                cercaIzq++;
            }
        }
        for (PuffField.Puff p : der) {
            if (p.x() < RegionKey.REGION_SIZE + PuffField.SEED_SPACING) {
                cercaDer++;
            }
        }
        check("el borde entre regiones vecinas tiene bultos de los dos lados",
                cercaIzq > 0 && cercaDer > 0, cercaIzq + " / " + cercaDer);
    }

    /** Perfiles graficos: que los tres se ordenen y que el tope de detalle mande. */
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
            float[] alfas = DensityField.sliceAlphas(level.slices());
            double resto = 1.0D;
            for (int i = 0; i < level.slices(); i++) {
                resto *= (1.0D - alfas[i]);
            }
            double acumulada = 1.0D - resto;
            peor = Math.max(peor, Math.abs(acumulada - objetivo));
            check("  " + level + " (" + level.slices() + " cortes) llega al objetivo",
                    Math.abs(acumulada - objetivo) < 1.0E-6D, acumulada);
        }
        // Es el defecto que se veia volando: cada cambio de nivel cambiaba el brillo de la nube.
        check("ningun cambio de nivel altera la opacidad", peor < 1.0E-6D, peor);

        // Se compara el corte central de cada nivel: es el que no arrastra el peso del extremo.
        check("mas cortes, menos alfa cada uno",
                DensityField.sliceAlpha(4, 8) < DensityField.sliceAlpha(2, 4)
                        && DensityField.sliceAlpha(2, 4) < DensityField.sliceAlpha(1, 2), "");
        check("un solo corte aporta toda la opacidad",
                Math.abs(DensityField.sliceAlpha(0, 1) - objetivo) < 1.0E-6D,
                DensityField.sliceAlpha(0, 1));
        check("cero cortes no rompe", DensityField.sliceAlpha(0, 0) > 0.0F,
                DensityField.sliceAlpha(0, 0));

        // La pila se desvanece hacia los extremos en vez de terminar en un canto duro. Ese canto
        // es lo que hacia que la capa, vista de canto, se leyera como un juego de laminas.
        for (LodLevel level : LodLevel.values()) {
            int n = level.slices();
            if (n < 4) {
                continue;
            }
            float[] alfas = DensityField.sliceAlphas(n);
            // La escalera de cuatro cortes es asimetrica -sus peldanos son {1,3,5,7} de ocho- asi
            // que el corte mas extremo no es el de indice cero. Se busca por altura, no por indice.
            int masExtremo = 0;
            int masCentral = 0;
            for (int i = 1; i < n; i++) {
                double d = Math.abs(DensityField.sliceT(i, n) - 0.5D);
                if (d > Math.abs(DensityField.sliceT(masExtremo, n) - 0.5D)) {
                    masExtremo = i;
                }
                if (d < Math.abs(DensityField.sliceT(masCentral, n) - 0.5D)) {
                    masCentral = i;
                }
            }
            check("  " + level + ": el corte mas extremo pesa menos que el mas central",
                    alfas[masExtremo] < alfas[masCentral] * 0.75F,
                    alfas[masExtremo] + " vs " + alfas[masCentral]);

            // Y el orden tiene que ser monotono: cuanto mas lejos del centro, menos aporta.
            boolean monotono = true;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double di = Math.abs(DensityField.sliceT(i, n) - 0.5D);
                    double dj = Math.abs(DensityField.sliceT(j, n) - 0.5D);
                    if (di < dj - 1.0E-9D && alfas[i] <= alfas[j]) {
                        monotono = false;
                    }
                }
            }
            check("  " + level + ": el peso baja de forma monotona hacia los extremos", monotono, "");
        }

        // El compuesto visto desde abajo nunca debe caer por debajo del cielo: eso era la banda gris.
        NoiseField ruido = new NoiseField(7L);
        DensityField campo = new DensityField(ruido, CloudLayerDef.LOW);
        double[] cielo = { 0.620D, 0.710D, 0.850D };
        float[] color = { CloudLayerDef.LOW.red(), CloudLayerDef.LOW.green(), CloudLayerDef.LOW.blue() };
        int cortes = LodLevel.HIGH.slices();
        float[] alfas = DensityField.sliceAlphas(cortes);
        double[] c = { cielo[0], cielo[1], cielo[2] };
        double masOscuro = 1.0D;
        for (int i = 0; i < cortes; i++) {
            float sombra = campo.shade(1.0D, i, cortes);
            float alfa = alfas[i];
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

    /**
     * Costuras entre niveles de detalle: la causa de las lineas rectas en el cielo.
     *
     * Dos regiones vecinas con distinto nivel comparten un borde recto de 256 bloques. Si las
     * alturas de sus cortes no coinciden, en esa banda se ven los dos juegos de planos
     * entrelazados y el alfa acumulado sube. La invariante que lo evita es que las alturas de un
     * nivel grueso sean un subconjunto exacto de las del fino.
     */
    static void seams() {
        NoiseField n = new NoiseField(3L);
        DensityField f = new DensityField(n, CloudLayerDef.LOW);
        LodLevel[] niveles = LodLevel.values();

        java.util.List<java.util.Set<Double>> alturas = new java.util.ArrayList<>();
        for (LodLevel l : niveles) {
            java.util.Set<Double> ys = new java.util.TreeSet<>();
            for (int i = 0; i < l.slices(); i++) {
                ys.add(Math.round(f.sliceHeight(i, l.slices()) * 1.0E6D) / 1.0E6D);
            }
            alturas.add(ys);
            check("  " + l + " tiene " + l.slices() + " alturas distintas",
                    ys.size() == l.slices(), ys.size());
        }

        for (int i = 0; i + 1 < niveles.length; i++) {
            java.util.Set<Double> fino = alturas.get(i);
            java.util.Set<Double> grueso = alturas.get(i + 1);
            check("las alturas de " + niveles[i + 1] + " son subconjunto de " + niveles[i],
                    fino.containsAll(grueso), grueso + " vs " + fino);

            java.util.Set<Double> union = new java.util.TreeSet<>(fino);
            union.addAll(grueso);
            // Es la cuenta que importa: cuantos planos distintos ve un rayo que cruza la costura.
            check("la costura " + niveles[i] + "|" + niveles[i + 1] + " no agrega planos",
                    union.size() == fino.size(), union.size() + " planos, el lado fino tiene " + fino.size());
        }

        // Coplanar no alcanza: si el umbral o el sombreado dependieran del indice, dos cortes a la
        // misma altura tendrian contenido distinto y la costura se veria igual.
        for (int i = 0; i + 1 < niveles.length; i++) {
            int cf = niveles[i].slices();
            int cg = niveles[i + 1].slices();
            boolean mismoUmbral = true;
            boolean mismaSombra = true;
            for (int ig = 0; ig < cg; ig++) {
                double t = DensityField.sliceT(ig, cg);
                int igual = -1;
                for (int iff = 0; iff < cf; iff++) {
                    if (Math.abs(DensityField.sliceT(iff, cf) - t) < 1.0E-9D) {
                        igual = iff;
                        break;
                    }
                }
                if (igual < 0) {
                    mismoUmbral = false;
                    mismaSombra = false;
                    break;
                }
                mismoUmbral &= f.sliceThreshold(ig, cg) <= f.sliceThreshold(igual, cf) + 1.0E-9D;
                // El sombreado de un corte suelto ya NO tiene por que coincidir entre niveles: la
                // correccion por nivel lo cambia a proposito. Lo que tiene que coincidir es la
                // columna entera, y eso se verifica abajo.
                mismaSombra &= f.shade(0.5D, ig, cg) > 0.0F;
            }
            check("el nivel grueso nunca recorta antes que el fino "
                    + niveles[i] + "|" + niveles[i + 1], mismoUmbral, "");

            // La silueta exterior la marca el umbral mas bajo. Si no coincide entre niveles, hay
            // densidades donde uno dibuja nube y el otro no, y eso es un borde de geometria que
            // ningun ajuste de color puede tapar.
            double minFino = 1.0D;
            double minGrueso = 1.0D;
            for (int k = 0; k < cf; k++) {
                minFino = Math.min(minFino, f.sliceThreshold(k, cf));
            }
            for (int k = 0; k < cg; k++) {
                minGrueso = Math.min(minGrueso, f.sliceThreshold(k, cg));
            }
            check("la silueta exterior es la misma en " + niveles[i] + "|" + niveles[i + 1],
                    Math.abs(minFino - minGrueso) < 1.0E-9D,
                    String.format(Locale.ROOT, "%.4f vs %.4f", minFino, minGrueso));
            check("sombreado valido en cortes coplanares " + niveles[i] + "|" + niveles[i + 1], mismaSombra, "");
        }

        // La condicion que de verdad importa en una costura: que la COLUMNA ENTERA de un lado se
        // vea igual que la del otro. Es lo que el ojo compara. No alcanza con que los cortes
        // coplanares sean iguales, porque los alfas por corte no lo son -no pueden serlo, es lo
        // que mantiene constante la opacidad- y el compuesto sale distinto igual.
        //
        // A densidad saturada la coincidencia tiene que ser exacta. A densidad parcial las dos
        // pilas tampoco tapan lo mismo, y eso ninguna correccion de color lo arregla, asi que ahi
        // se exige que el salto quede por debajo de lo que se nota.
        for (int i = 0; i + 1 < niveles.length; i++) {
            int cf = niveles[i].slices();
            int cg = niveles[i + 1].slices();
            double peorParcial = 0.0D;
            double peorSaturado = 0.0D;
            for (int paso = 0; paso <= 100; paso++) {
                double d = paso / 100.0D;
                double fino = columnaCompuesta(f, CloudLayerDef.LOW, cf, d);
                double grueso = columnaCompuesta(f, CloudLayerDef.LOW, cg, d);
                double salto = Math.abs(fino - grueso) * 255.0D;
                if (d >= 0.70D) {
                    peorSaturado = Math.max(peorSaturado, salto);
                } else {
                    peorParcial = Math.max(peorParcial, salto);
                }
            }
            check("la costura " + niveles[i] + "|" + niveles[i + 1] + " es invisible con nube densa",
                    peorSaturado < 0.05D,
                    String.format(Locale.ROOT, "%.4f niveles de gris", peorSaturado));
            check("la costura " + niveles[i] + "|" + niveles[i + 1] + " es invisible con nube tenue",
                    peorParcial < 0.05D,
                    String.format(Locale.ROOT, "%.4f niveles de gris", peorParcial));

            // Y lo mismo mirando desde arriba, que usa el otro orden de mezcla.
            double peorArriba = 0.0D;
            for (int paso = 0; paso <= 200; paso++) {
                double d = paso / 200.0D;
                peorArriba = Math.max(peorArriba,
                        Math.abs(columnaCompuesta(f, CloudLayerDef.LOW, cf, d, false)
                                - columnaCompuesta(f, CloudLayerDef.LOW, cg, d, false)) * 255.0D);
            }
            check("la costura " + niveles[i] + "|" + niveles[i + 1] + " tambien es invisible desde arriba",
                    peorArriba < 0.05D,
                    String.format(Locale.ROOT, "%.4f niveles de gris", peorArriba));
        }

        // Los cortes tienen que seguir repartidos por el espesor, no amontonados.
        for (LodLevel l : niveles) {
            double min = 1.0D;
            double max = 0.0D;
            for (int i = 0; i < l.slices(); i++) {
                double t = DensityField.sliceT(i, l.slices());
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
            check("  " + l + " reparte los cortes por el espesor",
                    min > 0.0D && max < 1.0D && (l.slices() == 1 || max - min > 0.45D),
                    String.format(Locale.ROOT, "%.4f..%.4f", min, max));
        }
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

        // Determinismo: es el requisito explicito de la Seccion 4.
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

        // Continuidad: sin saltos entre celdas, o se verian las costuras de la grilla.
        double worst = 0.0;
        for (int i = 0; i < 2000; i++) {
            double x = i * 0.01;
            worst = Math.max(worst, Math.abs(n.fbm(x, 4.0) - n.fbm(x + 0.01, 4.0)));
        }
        check("continuo (sin costuras de celda)", worst < 0.15, worst);

        // Precision lejos del origen: el hash trabaja sobre enteros, no sobre floats grandes.
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

        // Por debajo de cierto render distance manda el piso, asi que la proporcionalidad solo vale
        // por encima de el. El piso es generoso a proposito: la primera prueba real mostro que un
        // domo corto se ve recortado dentro del campo de vision, y eso se nota mucho mas que el
        // costo de unas regiones de mas.
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

        // Con el domo por defecto, ninguna celda llega a verse como una sabana en el cielo.
        int celdaMasGrande = 0;
        for (LodLevel l : LodLevel.values()) {
            celdaMasGrande = Math.max(celdaMasGrande, l.cellSize());
        }
        check("celda maxima acotada", celdaMasGrande <= 32, celdaMasGrande);
        check("piso minimo con render distance bajo",
                LodSelector.forRenderDistance(2, 1.0).maxDistance() >= 256.0,
                LodSelector.forRenderDistance(2, 1.0).maxDistance());

        // La banda es larga a proposito: es perspectiva atmosferica, y sobre todo es lo que evita
        // que el borde del domo quede concentrado en pocos pixeles. Medido en el simulador,
        // acortarla vuelve a producir lineas rectas en el cielo.
        check("cerca no hay fade", sel.distanceFade(300.0) == 1.0F, sel.distanceFade(300.0));
        check("la banda de desvanecimiento es larga",
                sel.distanceFade(500.0) > 0.85F && sel.distanceFade(500.0) < 1.0F,
                sel.distanceFade(500.0));
        // Y monotona, sin quiebres: un quiebre en el desvanecimiento tambien se lee como una linea.
        boolean monotona = true;
        float previo = 1.0F;
        for (double d = 0.0; d <= 1500.0; d += 5.0) {
            float f = sel.distanceFade(d);
            monotona &= f <= previo + 1.0E-6F;
            previo = f;
        }
        check("el desvanecimiento nunca sube", monotona, "");

        // El borde del domo se dibuja con margen: una region cuyo centro cae afuera todavia puede
        // tener medio lado adentro, y recortarla entera deja el borde escalonado por region.
        check("el margen de dibujo cubre media region",
                LodSelector.DRAW_MARGIN > RegionKey.REGION_SIZE * 0.70D,
                LodSelector.DRAW_MARGIN);
        check("el borde se dibuja aunque el centro caiga afuera",
                sel.levelForDrawing(sel.maxDistance() + 100.0) != null
                        && sel.levelForDrawing(sel.maxDistance() + LodSelector.DRAW_MARGIN + 1.0) == null,
                "");
        check("fade parcial cerca del borde",
                sel.distanceFade(1400.0) > 0.0F && sel.distanceFade(1400.0) < 1.0F, sel.distanceFade(1400.0));
        check("cero en el borde", sel.distanceFade(1500.0) == 0.0F, sel.distanceFade(1500.0));

        int high = LodLevel.HIGH.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        int barato = LodLevel.LOW.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
        // El ahorro ahora sale solo de los cortes, no del lado de celda: el lado es el mismo en
        // los tres niveles a proposito, porque cambiarlo cambia la silueta y eso se ve como un
        // borde entre regiones vecinas. Cuatro veces menos relleno sigue siendo el ahorro que
        // importa, que es el de pixeles pintados.
        check("el nivel mas barato cuesta cuatro veces menos que HIGH",
                barato * 4 <= high, high + " vs " + barato);
        check("y todos los niveles muestrean con el mismo lado de celda",
                LodLevel.HIGH.cellSize() == LodLevel.LOW.cellSize()
                        && LodLevel.MEDIUM.cellSize() == LodLevel.LOW.cellSize(),
                LodLevel.HIGH.cellSize() + "/" + LodLevel.MEDIUM.cellSize() + "/"
                        + LodLevel.LOW.cellSize());
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
