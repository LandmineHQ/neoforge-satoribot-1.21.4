package github.landminehq.satoribot;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = SatoriBot.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SatoriBot.MODID, value = Dist.CLIENT)
public class SatoriBotClient {
    public SatoriBotClient(ModContainer container) {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        SatoriBot.LOGGER.info("HELLO FROM CLIENT SETUP");
        SatoriBot.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
