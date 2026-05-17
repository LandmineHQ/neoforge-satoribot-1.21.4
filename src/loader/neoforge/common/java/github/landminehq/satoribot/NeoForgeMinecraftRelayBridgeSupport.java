package github.landminehq.satoribot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class NeoForgeMinecraftRelayBridgeSupport implements MinecraftRelayBridge {
    private final MinecraftServer server;
    private final NeoForgeHoverFactory hoverFactory;

    NeoForgeMinecraftRelayBridgeSupport(MinecraftServer server, NeoForgeHoverFactory hoverFactory) {
        this.server = Objects.requireNonNull(server, "server");
        this.hoverFactory = Objects.requireNonNull(hoverFactory, "hoverFactory");
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

    @Override
    public CompletableFuture<String> queryOnlinePlayers() {
        CompletableFuture<String> future = new CompletableFuture<>();
        this.server.execute(() -> {
            try {
                future.complete(buildOnlinePlayersMessage());
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private String buildOnlinePlayersMessage() {
        List<ServerPlayer> players = this.server.getPlayerList().getPlayers();
        int onlineCount = players.size();
        int maxPlayers = this.server.getPlayerList().getMaxPlayers();
        if (onlineCount == 0) {
            return "当前没有玩家在线。";
        }

        List<String> names = new ArrayList<>(onlineCount);
        for (ServerPlayer player : players) {
            names.add(player.getScoreboardName());
        }
        Collections.sort(names);
        return "在线玩家 (" + onlineCount + "/" + maxPlayers + "): " + String.join(", ", names);
    }

    private MutableComponent buildInboundMessage(String displayName, String userId, String message, String groupId) {
        String safeDisplayName = Objects.requireNonNull(displayName, "displayName");
        String safeUserId = Objects.requireNonNull(userId, "userId");
        String safeMessage = Objects.requireNonNull(message, "message");
        String safeGroupId = Objects.requireNonNull(groupId, "groupId");
        String sender = safeDisplayName + "(" + safeUserId + ")";
        HoverEvent hoverEvent = Objects.requireNonNull(this.hoverFactory.createGroupHover(safeGroupId), "hoverEvent");

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
