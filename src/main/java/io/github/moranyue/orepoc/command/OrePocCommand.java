package io.github.moranyue.orepoc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.moranyue.orepoc.config.OrePocConfig;
import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import io.github.moranyue.orepoc.generator.RemoteGenerator;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public class OrePocCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("orepoc")
            .then(literal("about")
                .executes(OrePocCommand::executeAbout))
            .then(literal("set_seed")
                .then(argument("seed", LongArgumentType.longArg())
                    .executes(OrePocCommand::executeSetSeed)))
            .then(literal("set_server")
                .then(argument("host", StringArgumentType.word())
                    .then(argument("port", IntegerArgumentType.integer(1, 65535))
                        .executes(OrePocCommand::executeSetServer))
                    .executes(ctx -> {
                        String host = StringArgumentType.getString(ctx, "host");
                        return executeSetServerRaw(ctx, host, 32567);
                    }))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(
                        "§7[OrePoC] §cUsage: /orepoc set_server <host>[:<port>]"));
                    return 1;
                }))
            .then(literal("set_mode")
                .then(literal("local")
                    .executes(ctx -> setMode(ctx, LocalWorldGenerator.Mode.LOCAL)))
                .then(literal("remote")
                    .executes(ctx -> setMode(ctx, LocalWorldGenerator.Mode.REMOTE)))
                .then(literal("none")
                    .executes(ctx -> setMode(ctx, LocalWorldGenerator.Mode.NONE))))
            .executes(OrePocCommand::executeAbout)
        );
    }

    private static int executeAbout(CommandContext<FabricClientCommandSource> ctx) {
        long seed = LocalWorldGenerator.INSTANCE.getSeed();
        boolean set = LocalWorldGenerator.INSTANCE.isSeedSet();
        LocalWorldGenerator.Mode mode = LocalWorldGenerator.INSTANCE.getMode();
        boolean remote = RemoteGenerator.INSTANCE.isAvailable();
        String serverInfo = remote ? "connected to " + RemoteGenerator.INSTANCE.getServerHost()
            + ":" + RemoteGenerator.INSTANCE.getServerPort() : "no remote server";
        ctx.getSource().sendFeedback(Component.literal(
            "§7[OrePoC] §fMode: §a" + mode.name().toLowerCase() +
            " §7| Seed: " + (set ? "§a" + seed : "§enone") +
            " §7| " + (remote ? "§a" : "§7") + serverInfo
        ));
        return 1;
    }

    private static int executeSetSeed(CommandContext<FabricClientCommandSource> ctx) {
        long seed = LongArgumentType.getLong(ctx, "seed");
        OrePocConfig.saveSeed(seed);
        LocalWorldGenerator.INSTANCE.setSeed(seed);
        ctx.getSource().sendFeedback(Component.literal(
            "§7[OrePoC] §aSeed set to " + seed));
        return 1;
    }

    private static int executeSetServer(CommandContext<FabricClientCommandSource> ctx) {
        String host = StringArgumentType.getString(ctx, "host");
        int port = IntegerArgumentType.getInteger(ctx, "port");
        return executeSetServerRaw(ctx, host, port);
    }

    private static int executeSetServerRaw(CommandContext<FabricClientCommandSource> ctx, String host, int port) {
        boolean ok = RemoteGenerator.INSTANCE.setServer(host, port);
        if (ok) {
            ctx.getSource().sendFeedback(Component.literal(
                "§7[OrePoC] §aConnected to " + host + ":" + port));
            // Auto-switch to remote mode on successful connection
            LocalWorldGenerator.INSTANCE.setMode(LocalWorldGenerator.Mode.REMOTE);
        } else {
            ctx.getSource().sendFeedback(Component.literal(
                "§7[OrePoC] §cFailed to connect to " + host + ":" + port));
        }
        return ok ? 1 : 0;
    }

    private static int setMode(CommandContext<FabricClientCommandSource> ctx,
                               LocalWorldGenerator.Mode mode) {
        LocalWorldGenerator.INSTANCE.setMode(mode);
        // Feedback is sent by LocalWorldGenerator.setMode() itself
        return 1;
    }
}
