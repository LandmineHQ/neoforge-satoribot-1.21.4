package github.landminehq.satoribot;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

interface NeoForgeRuntimeAdapter {
    ModConfigSpec configSpec();

    RelayConfig relayConfig();

    MinecraftRelayBridge createBridge(MinecraftServer server);
}
