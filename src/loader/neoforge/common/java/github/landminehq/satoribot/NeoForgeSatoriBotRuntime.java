package github.landminehq.satoribot;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

final class NeoForgeSatoriBotRuntime {
    static final Logger LOGGER = LogUtils.getLogger();

    private static SatoriRelayService relayService;

    private NeoForgeSatoriBotRuntime() {
    }

    static void initialize(IEventBus modEventBus, ModContainer modContainer, NeoForgeRuntimeAdapter adapter) {
        Objects.requireNonNull(modEventBus, "modEventBus");
        Objects.requireNonNull(modContainer, "modContainer");
        Objects.requireNonNull(adapter, "adapter");

        SatoriRelayService service = new SatoriRelayService(adapter.relayConfig(), LOGGER);
        relayService = service;

        modContainer.registerConfig(ModConfig.Type.COMMON, adapter.configSpec());
        NeoForge.EVENT_BUS.register(new ServerEvents(adapter, service));
    }

    static SatoriRelayService relayService() {
        SatoriRelayService service = relayService;
        if (service == null) {
            throw new IllegalStateException("SatoriBot has not been initialized yet.");
        }
        return service;
    }

    private static final class ServerEvents {
        private final NeoForgeRuntimeAdapter adapter;
        private final SatoriRelayService relayService;

        private ServerEvents(NeoForgeRuntimeAdapter adapter, SatoriRelayService relayService) {
            this.adapter = Objects.requireNonNull(adapter, "adapter");
            this.relayService = Objects.requireNonNull(relayService, "relayService");
        }

        @SubscribeEvent
        public void onServerStarted(ServerStartedEvent event) {
            this.relayService.start(this.adapter.createBridge(event.getServer()));
        }

        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            this.relayService.stop();
        }

        @SubscribeEvent
        public void onServerChat(ServerChatEvent event) {
            this.relayService.enqueueMinecraftMessage(event.getUsername(), event.getRawText());
        }

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            String playerName = event.getEntity().getScoreboardName();
            this.relayService.enqueueMinecraftSystemMessage(playerName + " 加入了游戏。");
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            String playerName = event.getEntity().getScoreboardName();
            this.relayService.enqueueMinecraftSystemMessage(playerName + " 离开了游戏。");
        }
    }
}
