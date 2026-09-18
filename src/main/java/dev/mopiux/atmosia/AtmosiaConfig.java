package dev.mopiux.atmosia;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuración de cliente (Sección 13.2 del documento de diseño).
 *
 * Todo lo que el documento llama "configurable" vive acá. Los valores se leen en caliente salvo
 * los que cambian la geometría ya generada, que requieren vaciar la caché: esos están marcados.
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

        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.DoubleValue distanceMultiplier;
        public final ForgeConfigSpec.DoubleValue coverageScale;
        public final ForgeConfigSpec.DoubleValue speedScale;
        public final ForgeConfigSpec.IntValue regionsPerFrame;
        public final ForgeConfigSpec.IntValue quadsPerFrame;
        public final ForgeConfigSpec.IntValue maxCachedRegions;
        public final ForgeConfigSpec.IntValue generationThreads;
        public final ForgeConfigSpec.LongValue seedOverride;
        public final ForgeConfigSpec.BooleanValue standDownForShaderPacks;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("Atmosia — nubes procedurales. Todo es de cliente.").push("general");

            this.enabled = builder
                    .comment("Con false, Atmosia no dibuja nada y devuelve las nubes vanilla.")
                    .define("enabled", true);

            this.standDownForShaderPacks = builder
                    .comment("Desactivarse cuando hay un shader pack activo.",
                             "Recomendado: competir por el cielo con un pack produce errores",
                             "irreproducibles, y el pack ya dibuja sus propias nubes.")
                    .define("standDownForShaderPacks", true);

            builder.pop().comment("Alcance y calidad").push("quality");

            this.distanceMultiplier = builder
                    .comment("Multiplicador sobre el render distance del jugador para decidir",
                             "hasta dónde llegan las nubes. No es una distancia fija a propósito:",
                             "generar nubes mucho más lejos de lo que el mundo dibuja es trabajo",
                             "tirado, y quedarse corto se ve peor que no tener nubes.")
                    .defineInRange("distanceMultiplier", 3.0D, 0.5D, 8.0D);

            this.coverageScale = builder
                    .comment("Multiplicador de cobertura. Mayor a 1 agranda las formaciones.",
                             "Requiere vaciar la caché para verse en las regiones ya generadas.")
                    .defineInRange("coverageScale", 1.0D, 0.2D, 2.0D);

            this.speedScale = builder
                    .comment("Multiplicador de la velocidad de deriva de todas las capas.")
                    .defineInRange("speedScale", 1.0D, 0.0D, 8.0D);

            builder.pop().comment("Presupuesto de rendimiento (Sección 8.2)").push("budget");

            this.regionsPerFrame = builder
                    .comment("Máximo de regiones que se suben a GPU por frame.",
                             "Bajarlo hace que el cielo se llene más despacio, nunca que tironee.")
                    .defineInRange("regionsPerFrame", 2, 1, 16);

            this.quadsPerFrame = builder
                    .comment("Máximo de cuádruples construidos por frame.")
                    .defineInRange("quadsPerFrame", 24_000, 1_000, 500_000);

            this.maxCachedRegions = builder
                    .comment("Regiones vivas en caché. Más es más memoria y menos regeneración.")
                    .defineInRange("maxCachedRegions", 384, 16, 2048);

            this.generationThreads = builder
                    .comment("Hilos de generación de densidad. 0 usa la mitad de los núcleos.")
                    .defineInRange("generationThreads", 0, 0, 16);

            builder.pop().comment("Determinismo").push("seed");

            this.seedOverride = builder
                    .comment("Seed fija para las nubes. 0 la deriva del mundo o servidor.",
                             "Atmosia es de cliente: dos jugadores del mismo servidor no ven",
                             "necesariamente las mismas nubes. Es una decisión consciente de",
                             "diseño, no un error. Fijar la misma seed a mano las iguala.")
                    .defineInRange("seedOverride", 0L, Long.MIN_VALUE, Long.MAX_VALUE);

            builder.pop();
        }
    }
}
