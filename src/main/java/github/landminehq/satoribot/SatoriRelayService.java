package github.landminehq.satoribot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

final class SatoriRelayService {
    private static final int OP_EVENT = 0;
    private static final int OP_PING = 1;
    private static final int OP_PONG = 2;
    private static final int OP_IDENTIFY = 3;
    private static final int OP_READY = 4;
    private static final long HEARTBEAT_SECONDS = 10L;
    private static final long RECONNECT_DELAY_SECONDS = 5L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new RelayThreadFactory());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Object bufferLock = new Object();
    private final List<String> outboundBuffer = new ArrayList<>();

    private volatile MinecraftServer server;
    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile long lastSn;
    private volatile String loginPlatform;
    private volatile String selfUserId;
    private volatile ScheduledFuture<?> pingFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> flushFuture;

    public synchronized void start(MinecraftServer server) {
        stop();
        this.server = server;
        this.running = true;
        this.lastSn = 0L;
        connectWebSocket();
    }

    public synchronized void stop() {
        this.running = false;
        cancelFuture(this.pingFuture);
        cancelFuture(this.reconnectFuture);
        this.pingFuture = null;
        this.reconnectFuture = null;
        this.loginPlatform = null;
        this.selfUserId = null;

        synchronized (this.bufferLock) {
            cancelFuture(this.flushFuture);
            this.flushFuture = null;
            this.outboundBuffer.clear();
        }

        WebSocket currentSocket = this.webSocket;
        this.webSocket = null;
        if (currentSocket != null) {
            currentSocket.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }

        this.server = null;
    }

    public void enqueueMinecraftMessage(String username, String rawText) {
        if (!this.running) {
            return;
        }

        String cleanUser = username == null ? "" : username.trim();
        String cleanMessage = rawText == null ? "" : rawText.trim();
        if (cleanUser.isEmpty() || cleanMessage.isEmpty()) {
            return;
        }

        synchronized (this.bufferLock) {
            this.outboundBuffer.add(cleanUser + ": " + cleanMessage);
            if (this.flushFuture == null || this.flushFuture.isDone()) {
                this.flushFuture = this.scheduler.schedule(this::flushBufferedMessages, Config.mergeWindowSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private void flushBufferedMessages() {
        List<String> batch;
        synchronized (this.bufferLock) {
            this.flushFuture = null;
            if (this.outboundBuffer.isEmpty()) {
                return;
            }
            batch = List.copyOf(this.outboundBuffer);
            this.outboundBuffer.clear();
        }

        sendMergedMinecraftMessages(batch);
    }

    private void sendMergedMinecraftMessages(List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (!canSendHttpMessages()) {
            requeue(batch);
            return;
        }

        URI endpoint;
        try {
            endpoint = buildMessageCreateUri();
        } catch (IllegalArgumentException ex) {
            SatoriBot.LOGGER.error("Invalid Satori websocket url in config: {}", Config.satoriWsUrl(), ex);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("channel_id", Config.groupId());
        payload.addProperty("content", SatoriText.escapePlainText(String.join("\n", batch)));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Satori-Platform", this.loginPlatform)
                .header("Satori-User-ID", this.selfUserId)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));

        if (!Config.satoriToken().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + Config.satoriToken());
        }

        httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        SatoriBot.LOGGER.error("Failed to forward Minecraft chat to Satori.", throwable);
                        requeue(batch);
                        return;
                    }

                    int status = response.statusCode();
                    if (status / 100 == 2) {
                        return;
                    }

                    SatoriBot.LOGGER.error("Satori message.create failed with status {}: {}", status, response.body());
                    if (status >= 500 || status == 429) {
                        requeue(batch);
                    }
                });
    }

    private void requeue(List<String> batch) {
        if (!this.running || batch.isEmpty()) {
            return;
        }

        synchronized (this.bufferLock) {
            List<String> combined = new ArrayList<>(batch.size() + this.outboundBuffer.size());
            combined.addAll(batch);
            combined.addAll(this.outboundBuffer);
            this.outboundBuffer.clear();
            this.outboundBuffer.addAll(combined);
            if (this.flushFuture == null || this.flushFuture.isDone()) {
                this.flushFuture = this.scheduler.schedule(this::flushBufferedMessages, Config.mergeWindowSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private boolean canSendHttpMessages() {
        return this.running
                && !Config.groupId().isEmpty()
                && !Config.satoriWsUrl().isEmpty()
                && this.loginPlatform != null
                && !this.loginPlatform.isBlank()
                && this.selfUserId != null
                && !this.selfUserId.isBlank();
    }

    private synchronized void connectWebSocket() {
        if (!this.running) {
            return;
        }

        URI wsUri;
        try {
            wsUri = URI.create(normalizeWsUrl(Config.satoriWsUrl()));
        } catch (IllegalArgumentException ex) {
            SatoriBot.LOGGER.error("Invalid Satori websocket url in config: {}", Config.satoriWsUrl(), ex);
            scheduleReconnect();
            return;
        }

        SatoriBot.LOGGER.info("Connecting to Satori websocket: {}", wsUri);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, new SatoriWebSocketListener())
                .whenComplete((socket, throwable) -> {
                    if (throwable != null) {
                        SatoriBot.LOGGER.error("Unable to connect to Satori websocket.", throwable);
                        scheduleReconnect();
                    }
                });
    }

    private void handleWebSocketPayload(String payload) {
        JsonObject packet;
        try {
            packet = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException ex) {
            SatoriBot.LOGGER.error("Invalid Satori websocket payload: {}", payload, ex);
            return;
        }

        int op = getAsInt(packet, "op", -1);
        JsonObject body = getAsObject(packet, "body");

        switch (op) {
            case OP_EVENT -> handleEvent(body);
            case OP_READY -> handleReady(body);
            case OP_PONG -> {
            }
            default -> SatoriBot.LOGGER.debug("Ignoring Satori opcode {}", op);
        }
    }

    private void handleReady(JsonObject body) {
        JsonArray logins = getAsArray(body, "logins");
        if (logins == null || logins.isEmpty()) {
            SatoriBot.LOGGER.warn("Received READY without login context.");
            return;
        }

        for (JsonElement element : logins) {
            if (element.isJsonObject() && updateLoginContext(element.getAsJsonObject())) {
                SatoriBot.LOGGER.info("Satori login ready: {} / {}", this.loginPlatform, this.selfUserId);
                return;
            }
        }

        SatoriBot.LOGGER.warn("Received READY but could not extract a valid login context.");
    }

    private void handleEvent(JsonObject body) {
        if (body == null) {
            return;
        }

        this.lastSn = getAsLong(body, "sn", this.lastSn);

        JsonObject login = getAsObject(body, "login");
        if (login != null) {
            updateLoginContext(login);
        }

        if (!Objects.equals("message-created", getAsString(body, "type"))) {
            return;
        }

        String configuredGroupId = Config.groupId();
        if (configuredGroupId.isEmpty()) {
            return;
        }

        JsonObject channel = getAsObject(body, "channel");
        JsonObject guild = getAsObject(body, "guild");
        String channelId = channel == null ? "" : getAsString(channel, "id");
        String guildId = guild == null ? "" : getAsString(guild, "id");
        if (!configuredGroupId.equals(channelId) && !configuredGroupId.equals(guildId)) {
            return;
        }

        JsonObject user = getAsObject(body, "user");
        JsonObject message = getAsObject(body, "message");
        if (user == null && message != null) {
            user = getAsObject(message, "user");
        }
        if (message == null || user == null) {
            return;
        }

        String userId = getAsString(user, "id");
        if (userId.isEmpty()) {
            return;
        }
        if (userId.equals(this.selfUserId)) {
            return;
        }

        String displayName = firstNonBlank(getAsString(user, "nick"), getAsString(user, "name"), userId);
        String plainText = SatoriText.toPlainText(getAsString(message, "content"));
        if (plainText.isEmpty()) {
            return;
        }

        relayToMinecraft(displayName, userId, plainText);
    }

    private boolean updateLoginContext(JsonObject login) {
        String platform = getAsString(login, "platform");
        JsonObject user = getAsObject(login, "user");
        String userId = user == null ? "" : getAsString(user, "id");
        if (platform.isEmpty() || userId.isEmpty()) {
            return false;
        }

        this.loginPlatform = platform;
        this.selfUserId = userId;
        return true;
    }

    private void relayToMinecraft(String displayName, String userId, String plainText) {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }

        String[] lines = plainText.split("\\n");
        currentServer.execute(() -> {
            if (this.server == null) {
                return;
            }
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                this.server.getPlayerList().broadcastSystemMessage(
                        Objects.requireNonNull(buildInboundMessage(displayName, userId, line.trim())),
                        false
                );
            }
        });
    }

    private MutableComponent buildInboundMessage(String displayName, String userId, String message) {
        String safeDisplayName = Objects.requireNonNull(displayName);
        String safeUserId = Objects.requireNonNull(userId);
        String safeMessage = Objects.requireNonNull(message);

        return Objects.requireNonNull(Component.empty())
                .append(Objects.requireNonNull(Component.literal(safeDisplayName).withStyle(ChatFormatting.AQUA)))
                .append(Objects.requireNonNull(Component.literal("(").withStyle(ChatFormatting.DARK_GRAY)))
                .append(Objects.requireNonNull(Component.literal(safeUserId).withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(Component.literal("): ").withStyle(ChatFormatting.DARK_GRAY)))
                .append(Objects.requireNonNull(Component.literal(safeMessage).withStyle(ChatFormatting.WHITE)));
    }

    private URI buildMessageCreateUri() {
        URI wsUri = URI.create(normalizeWsUrl(Config.satoriWsUrl()));
        String httpScheme = "wss".equalsIgnoreCase(wsUri.getScheme()) ? "https" : "http";
        String path = wsUri.getPath() == null ? "" : wsUri.getPath();
        if (path.endsWith("/events")) {
            path = path.substring(0, path.length() - "/events".length());
        }
        if (path.isEmpty()) {
            path = "/v1";
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        path = path + "/message.create";

        try {
            return new URI(httpScheme, wsUri.getUserInfo(), wsUri.getHost(), wsUri.getPort(), path, null, null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Unable to derive Satori HTTP API endpoint.", ex);
        }
    }

    private String normalizeWsUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Satori websocket url is blank.");
        }

        URI uri = URI.create(rawUrl.trim());
        String scheme = uri.getScheme();
        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Satori websocket url must use ws:// or wss://.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Satori websocket url is missing a host.");
        }

        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/v1/events";
        } else if (path.endsWith("/v1")) {
            path = path + "/events";
        } else if (path.endsWith("/v1/")) {
            path = path + "events";
        } else if (!path.endsWith("/events")) {
            path = path.replaceAll("/+$", "") + "/v1/events";
        }

        try {
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), path, uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid Satori websocket url.", ex);
        }
    }

    private void sendIdentify() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_IDENTIFY);

        JsonObject body = new JsonObject();
        if (!Config.satoriToken().isEmpty()) {
            body.addProperty("token", Config.satoriToken());
        }
        if (this.lastSn > 0) {
            body.addProperty("sn", this.lastSn);
        }
        payload.add("body", body);

        sendJson(payload);
    }

    private void sendPing() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_PING);
        sendJson(payload);
    }

    private void sendJson(JsonObject payload) {
        WebSocket currentSocket = this.webSocket;
        if (currentSocket == null) {
            return;
        }
        currentSocket.sendText(payload.toString(), true)
                .exceptionally(throwable -> {
                    SatoriBot.LOGGER.error("Failed to send websocket payload to Satori.", throwable);
                    return null;
                });
    }

    private synchronized void startHeartbeat() {
        cancelFuture(this.pingFuture);
        this.pingFuture = this.scheduler.scheduleAtFixedRate(this::sendPing, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void scheduleReconnect() {
        if (!this.running) {
            return;
        }
        if (this.reconnectFuture != null && !this.reconnectFuture.isDone()) {
            return;
        }

        this.reconnectFuture = this.scheduler.schedule(() -> {
            this.reconnectFuture = null;
            connectWebSocket();
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static JsonObject getAsObject(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonObject()) {
            return null;
        }
        return json.getAsJsonObject(key);
    }

    private static JsonArray getAsArray(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return null;
        }
        return json.getAsJsonArray(key);
    }

    private static String getAsString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString().trim();
        } catch (UnsupportedOperationException ex) {
            return "";
        }
    }

    private static int getAsInt(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static long getAsLong(JsonObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private final class SatoriWebSocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            SatoriRelayService.this.webSocket = webSocket;
            sendIdentify();
            startHeartbeat();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            this.buffer.append(data);
            if (last) {
                handleWebSocketPayload(this.buffer.toString());
                this.buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            SatoriRelayService.this.webSocket = null;
            cancelFuture(SatoriRelayService.this.pingFuture);
            SatoriRelayService.this.pingFuture = null;
            if (running) {
                SatoriBot.LOGGER.warn("Satori websocket closed: {} {}", statusCode, reason);
                scheduleReconnect();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            SatoriBot.LOGGER.error("Satori websocket error.", error);
        }
    }

    private static final class RelayThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "satoribot-relay");
            thread.setDaemon(true);
            return thread;
        }
    }
}
