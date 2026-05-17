package github.landminehq.satoribot;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;

final class NeoForgeMinecraftRelayBridge extends AbstractNeoForgeMinecraftRelayBridge {
    NeoForgeMinecraftRelayBridge(MinecraftServer server) {
        super(server);
    }

    @Override
    protected HoverEvent createGroupHover(String groupId) {
        return new HoverEvent.ShowText(Component.literal("群" + Objects.requireNonNull(groupId, "groupId")));
    }
}
