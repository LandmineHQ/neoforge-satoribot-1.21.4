package github.landminehq.satoribot;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

final class NeoForgeVersionAdapter implements NeoForgeSatoriBotAdapter {
    private final NeoForgeRelayConfig config = new NeoForgeRelayConfig();

    @Override
    public ModConfigSpec configSpec() {
        return NeoForgeRelayConfig.SPEC;
    }

    @Override
    public RelayConfig relayConfig() {
        return this.config;
    }

    @Override
    public MinecraftRelayBridge createBridge(MinecraftServer server) {
        return new NeoForgeMinecraftRelayBridge(server);
    }
}
