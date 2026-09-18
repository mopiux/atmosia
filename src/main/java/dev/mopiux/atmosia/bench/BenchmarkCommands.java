package dev.mopiux.atmosia.bench;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Comandos de cliente para disparar una corrida.
 *
 * Son comandos de cliente y no de servidor a propósito: el benchmark mide el render local y no
 * tiene por qué existir en un servidor.
 *
 * Uso:
 *   /atmosiabench list
 *   /atmosiabench run &lt;escenario&gt; [calentamiento_s] [duracion_s]
 *   /atmosiabench abort
 */
@Mod.EventBusSubscriber(Dist.CLIENT)
public final class BenchmarkCommands {

    private BenchmarkCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("atmosiabench")
                .then(Commands.literal("list").executes(ctx -> {
                    StringBuilder names = new StringBuilder();
                    for (BenchmarkScenario scenario : BenchmarkScenario.values()) {
                        names.append(names.isEmpty() ? "" : ", ").append(scenario.id());
                    }
                    reply(ctx.getSource(), "Escenarios: " + names);
                    return 1;
                }))
                .then(Commands.literal("abort").executes(ctx -> {
                    BenchmarkRunner.get().abort("pedido por el jugador");
                    return 1;
                }))
                .then(Commands.literal("run")
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "scenario"), 5.0D, 30.0D))
                                .then(Commands.argument("warmup", DoubleArgumentType.doubleArg(0.0D, 60.0D))
                                        .then(Commands.argument("duration", DoubleArgumentType.doubleArg(1.0D, 600.0D))
                                                .executes(ctx -> run(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "scenario"),
                                                        DoubleArgumentType.getDouble(ctx, "warmup"),
                                                        DoubleArgumentType.getDouble(ctx, "duration")))))));

        event.getDispatcher().register(root);
    }

    private static int run(CommandSourceStack source, String id, double warmup, double duration) {
        BenchmarkScenario scenario = BenchmarkScenario.byId(id);
        if (scenario == null) {
            reply(source, "Escenario desconocido: " + id + ". Probá /atmosiabench list");
            return 0;
        }
        if (!BenchmarkRunner.get().start(scenario, warmup, duration)) {
            reply(source, "No se pudo iniciar: ya hay una corrida en curso o no hay mundo cargado.");
            return 0;
        }
        return 1;
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[Atmosia] " + message), false);
    }
}
