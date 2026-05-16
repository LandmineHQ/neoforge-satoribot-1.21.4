package github.landminehq.satoribot;

import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

final class NeoForgeMinecraftRelayBridge implements MinecraftRelayBridge {
    private final MinecraftServer server;

    NeoForgeMinecraftRelayBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
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

    private MutableComponent buildInboundMessage(String displayName, String userId, String message, String groupId) {
        String safeDisplayName = Objects.requireNonNull(displayName);
        String safeUserId = Objects.requireNonNull(userId);
        String safeMessage = Objects.requireNonNull(message);
        String safeGroupId = Objects.requireNonNull(groupId);
        String sender = safeDisplayName + "(" + safeUserId + ")";
        String hoverText = "群" + safeGroupId;
        HoverEvent hoverEvent = new HoverEvent.ShowText(Objects.requireNonNull(Component.literal(hoverText)));

        return Objects.requireNonNull(Component.empty())
                .append(Objects.requireNonNull(Component.literal("<").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(
                        Component.literal(sender).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withHoverEvent(Objects.requireNonNull(hoverEvent)))))
                .append(Objects.requireNonNull(Component.literal("> ").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(Component.literal(safeMessage).withStyle(ChatFormatting.WHITE)));
    }
}
