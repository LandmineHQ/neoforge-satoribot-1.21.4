package github.landminehq.satoribot;

public interface MinecraftRelayBridge {
    void execute(Runnable task);

    void broadcastInboundMessage(String displayName, String userId, String message, String groupId);
}
