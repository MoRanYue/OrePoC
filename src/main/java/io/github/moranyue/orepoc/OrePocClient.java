package io.github.moranyue.orepoc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import io.github.moranyue.orepoc.command.OrePocCommand;
import io.github.moranyue.orepoc.config.OrePocConfig;
import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrePoc Mod — Client-only entrypoint.
 */
public class OrePocClient implements ClientModInitializer {
    public static final String MOD_ID = "orepoc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        // Register client command with tab completion
        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, buildContext) -> OrePocCommand.register(dispatcher)
        );

        // Check if local Paper server (OrePocServer) is available
        io.github.moranyue.orepoc.generator.RemoteGenerator remoteGen =
            io.github.moranyue.orepoc.generator.RemoteGenerator.INSTANCE;
        boolean remoteAvailable = remoteGen.checkConnection();
        if (remoteAvailable) {
            LOGGER.info("Connected to OrePoC Server (local Paper instance)");
        } else {
            LOGGER.info("No OrePoC Server detected, using local generation");
        }

        // Load saved seed on startup
        if (OrePocConfig.isSeedSet()) {
            long savedSeed = OrePocConfig.loadSeed();
            LocalWorldGenerator.INSTANCE.setSeed(savedSeed);
            LOGGER.info("Loaded saved seed from config: {}", savedSeed);
        }

        LOGGER.info("OrePoc client initialized. Use /orepoc set_seed <seed>.");
    }
}
