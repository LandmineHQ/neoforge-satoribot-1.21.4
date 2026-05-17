package github.landminehq.satoribot;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;

final class NeoForgeMinecraftRelayBridge implements MinecraftRelayBridge {
    private final NeoForgeMinecraftRelayBridgeSupport support;

    NeoForgeMinecraftRelayBridge(MinecraftServer server) {
        this.support = new NeoForgeMinecraftRelayBridgeSupport(server, NeoForgeMinecraftRelayBridge::createGroupHover);
    }

    @Override
    public void execute(Runnable task) {
        this.support.execute(task);
    }

    @Override
    public void broadcastInboundMessage(String displayName, String userId, String message, String groupId) {
        this.support.broadcastInboundMessage(displayName, userId, message, groupId);
    }

    private static HoverEvent createGroupHover(String groupId) {
        String safeGroupId = Objects.requireNonNull(groupId, "groupId");
        HoverEvent hoverEvent = new HoverEvent(
                Objects.requireNonNull(HoverEvent.Action.SHOW_TEXT),
                Objects.requireNonNull(Component.literal("群" + safeGroupId)));
        return Objects.requireNonNull(hoverEvent);
    }
}
