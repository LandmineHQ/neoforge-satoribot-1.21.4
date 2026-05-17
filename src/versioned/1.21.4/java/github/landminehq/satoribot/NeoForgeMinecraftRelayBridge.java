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
        String safeGroupId = Objects.requireNonNull(groupId, "groupId");
        HoverEvent hoverEvent = new HoverEvent(
                Objects.requireNonNull(HoverEvent.Action.SHOW_TEXT),
                Objects.requireNonNull(Component.literal("群" + safeGroupId)));
        return Objects.requireNonNull(hoverEvent);
    }
}
