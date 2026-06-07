package io.github.moranyue.orepoc.mixin;

import io.github.moranyue.orepoc.config.OrePocConfig;
import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatScreenMixin is no longer needed for command handling.
 * Commands are now registered via Fabric's ClientCommandRegistrationCallback.
 * This mixin is kept empty to avoid removing the file and breaking references.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    // Commands are now handled by OrePocCommand via ClientCommandRegistrationCallback
}
