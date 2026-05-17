package github.landminehq.satoribot;

import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

abstract class AbstractNeoForgeMinecraftRelayBridge implements MinecraftRelayBridge {
    private final MinecraftServer server;

    AbstractNeoForgeMinecraftRelayBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void execute(Runnable task) {
        this.server.execute(task);
    }

    @Override
    public void broadcastInboundMessage(String displayName, String userId, String message, String groupId) {
        this.server.getPlayerList().broadcastSystemMessage(
                Objects.requireNonNull(buildInboundMessage(displayName, userId, message, groupId)),
                false);
    }

    protected abstract HoverEvent createGroupHover(String groupId);

    private MutableComponent buildInboundMessage(String displayName, String userId, String message, String groupId) {
        String safeDisplayName = Objects.requireNonNull(displayName, "displayName");
        String safeUserId = Objects.requireNonNull(userId, "userId");
        String safeMessage = Objects.requireNonNull(message, "message");
        String safeGroupId = Objects.requireNonNull(groupId, "groupId");
        String sender = safeDisplayName + "(" + safeUserId + ")";
        HoverEvent hoverEvent = Objects.requireNonNull(createGroupHover(safeGroupId), "hoverEvent");

        return Objects.requireNonNull(Component.empty())
                .append(Objects.requireNonNull(Component.literal("<").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(
                        Component.literal(sender).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withHoverEvent(hoverEvent))))
                .append(Objects.requireNonNull(Component.literal("> ").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(Component.literal(safeMessage).withStyle(ChatFormatting.WHITE)));
    }
}
