package github.landminehq.satoribot;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SatoriBot.MODID)
public final class SatoriBot extends AbstractNeoForgeSatoriBot {
    public static final String MODID = "satoribot";
    public static final Logger LOGGER = AbstractNeoForgeSatoriBot.LOGGER;

    public SatoriBot(IEventBus modEventBus, ModContainer modContainer) {
        super(modEventBus, modContainer, new NeoForgeVersionAdapter());
    }

    public static SatoriRelayService relayService() {
        return AbstractNeoForgeSatoriBot.relayService();
    }
}
