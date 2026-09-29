package dev.mcai.assistant;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;

public final class MinecraftAiAssistant implements ModInitializer {
    public static final String MOD_ID = "mc_ai_assistant";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        try {
            AssistantService service = new AssistantService(FabricLoader.getInstance().getConfigDir(),
                    Clock.systemUTC());
            AiCommands.register(service);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> service.close());
            LOGGER.info("Minecraft AI Assistant initialized");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to initialize Minecraft AI Assistant", exception);
        }
    }
}
