package github.landminehq.satoribot;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(SatoriBot.MODID)
public final class SatoriBot {
    public static final String MODID = "satoribot";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final SatoriRelayService RELAY_SERVICE = new SatoriRelayService();

    public SatoriBot(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(new ServerEvents());
    }

    public static SatoriRelayService relayService() {
        return RELAY_SERVICE;
    }

    public static final class ServerEvents {
        private ServerEvents() {
        }

        @SubscribeEvent
        public void onServerStarted(ServerStartedEvent event) {
            RELAY_SERVICE.start(event.getServer());
        }

        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            RELAY_SERVICE.stop();
        }

        @SubscribeEvent
        public void onServerChat(ServerChatEvent event) {
            RELAY_SERVICE.enqueueMinecraftMessage(event.getUsername(), event.getRawText());
        }
    }
}
