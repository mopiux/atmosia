package dev.mopiux.atmosia;

import dev.mopiux.atmosia.core.CloudMode;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.QualityProfile;
import dev.mopiux.atmosia.core.RenderTechnique;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuracion de cliente (Seccion 13.2 del documento de diseno).
 *
 * Todo lo que el documento llama "configurable" vive aca. Los valores se leen en caliente salvo
 * los que cambian la geometria ya generada, que requieren vaciar la cache: esos estan marcados.
 */
public final class AtmosiaConfig {

    public static final ForgeConfigSpec SPEC;
    public static final Client CLIENT;

    static {
        Pair<Client, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        SPEC = pair.getRight();
    }

    private AtmosiaConfig() {
    }

    public static final class Client {

        public final ForgeConfigSpec.EnumValue<CloudMode> cloudMode;
        public final ForgeConfigSpec.EnumValue<RenderTechnique> renderTechnique;
        public final ForgeConfigSpec.EnumValue<QualityProfile> qualityProfile;
        public final ForgeConfigSpec.DoubleValue distanceMultiplier;
        public final ForgeConfigSpec.DoubleValue coverageScale;
        public final ForgeConfigSpec.DoubleValue speedScale;
        public final ForgeConfigSpec.IntValue regionsPerFrame;
        public final ForgeConfigSpec.IntValue quadsPerFrame;
        public final ForgeConfigSpec.IntValue maxCachedRegions;
        public final ForgeConfigSpec.IntValue generationThreads;
        public final ForgeConfigSpec.LongValue seedOverride;
        public final ForgeConfigSpec.BooleanValue standDownForShaderPacks;

        /**
         * Los valores de rendimiento que efectivamente rigen, con el perfil ya aplicado.
         *
         * Un solo lugar resuelve "perfil o archivo", asi que no hay forma de que una parte del
         * renderer lea el perfil y otra los valores sueltos.
         */
        public QualityProfile.Settings resolvedQuality() {
            QualityProfile.Settings fromFile = new QualityProfile.Settings(
                    this.distanceMultiplier.get(),
                    LodLevel.HIGH,
                    this.regionsPerFrame.get(),
                    this.quadsPerFrame.get(),
                    this.maxCachedRegions.get());
            return this.qualityProfile.get().resolve(fromFile);
        }

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("Atmosia - nubes procedurales. Todo es de cliente.").push("general");

            this.cloudMode = builder
                    .comment("Que nubes se dibujan.",
                             "ATMOSIA: las del mod, con las vanilla apagadas.",
                             "VANILLA: las originales del juego, como si el mod no estuviera.",
                             "NONE: ninguna de las dos, cielo despejado.",
                             "VANILLA es el modo a usar para medir la linea base del benchmark.")
                    .defineEnum("cloudMode", CloudMode.ATMOSIA);

            this.renderTechnique = builder
                    .comment("Como se dibuja el cielo. Es la decision estructural del mod.",
                             "SLICES: planos horizontales apilados. La tecnica original.",
                             "SPRITES: bultos con textura que miran a la camara. Sin grilla.",
                             "RAYMARCH: volumen por rayos, un solo dibujado. Sin geometria.",
                             "Las tres usan el mismo campo de densidad y las mismas capas: lo",
                             "unico que cambia es como se dibuja, asi que se pueden comparar.")
                    .defineEnum("renderTechnique", RenderTechnique.SPRITES);

            this.standDownForShaderPacks = builder
                    .comment("Desactivarse cuando hay un shader pack activo.",
                             "Recomendado: competir por el cielo con un pack produce errores",
                             "irreproducibles, y el pack ya dibuja sus propias nubes.")
                    .define("standDownForShaderPacks", true);

            builder.pop().comment("Alcance y calidad").push("quality");

            this.qualityProfile = builder
                    .comment("Perfil grafico. LOW, MEDIUM y HIGH mandan sobre distanceMultiplier y",
                             "sobre todo el bloque [budget]: los valores sueltos de abajo se",
                             "ignoran. Con CUSTOM pasa al reves y manda el archivo.")
                    .defineEnum("qualityProfile", QualityProfile.MEDIUM);

            this.distanceMultiplier = builder
                    .comment("Solo se usa con qualityProfile = CUSTOM.",
                             "Multiplicador sobre el render distance del jugador para decidir",
                             "hasta donde llegan las nubes. No es una distancia fija a proposito:",
                             "generar nubes mucho mas lejos de lo que el mundo dibuja es trabajo",
                             "tirado, y quedarse corto se ve peor que no tener nubes.")
                    .defineInRange("distanceMultiplier", 3.0D, 0.5D, 8.0D);

            this.coverageScale = builder
                    .comment("Multiplicador de cobertura. Mayor a 1 agranda las formaciones.",
                             "Requiere vaciar la cache para verse en las regiones ya generadas.")
                    .defineInRange("coverageScale", 1.0D, 0.2D, 2.0D);

            this.speedScale = builder
                    .comment("Multiplicador de la velocidad de deriva de todas las capas.")
                    .defineInRange("speedScale", 1.0D, 0.0D, 8.0D);

            builder.pop()
                    .comment("Presupuesto de rendimiento (Seccion 8.2).",
                             "Todo este bloque solo se usa con qualityProfile = CUSTOM.")
                    .push("budget");

            this.regionsPerFrame = builder
                    .comment("Maximo de regiones que se suben a GPU por frame.",
                             "Bajarlo hace que el cielo se llene mas despacio, nunca que tironee.")
                    .defineInRange("regionsPerFrame", 2, 1, 16);

            this.quadsPerFrame = builder
                    .comment("Maximo de cuadruples construidos por frame.")
                    .defineInRange("quadsPerFrame", 24_000, 1_000, 500_000);

            this.maxCachedRegions = builder
                    .comment("Regiones vivas en cache. Mas es mas memoria y menos regeneracion.")
                    .defineInRange("maxCachedRegions", 384, 16, 2048);

            this.generationThreads = builder
                    .comment("Hilos de generacion de densidad. 0 usa la mitad de los nucleos.")
                    .defineInRange("generationThreads", 0, 0, 16);

            builder.pop().comment("Determinismo").push("seed");

            this.seedOverride = builder
                    .comment("Seed fija para las nubes. 0 la deriva del mundo o servidor.",
                             "Atmosia es de cliente: dos jugadores del mismo servidor no ven",
                             "necesariamente las mismas nubes. Es una decision consciente de",
                             "diseno, no un error. Fijar la misma seed a mano las iguala.")
                    .defineInRange("seedOverride", 0L, Long.MIN_VALUE, Long.MAX_VALUE);

            builder.pop();
        }
    }
}
