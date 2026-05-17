package github.landminehq.satoribot;

import java.util.concurrent.CompletableFuture;

public interface MinecraftRelayBridge {
    void execute(Runnable task);

    void broadcastInboundMessage(String displayName, String userId, String message, String groupId);

    CompletableFuture<String> queryOnlinePlayers();
}
