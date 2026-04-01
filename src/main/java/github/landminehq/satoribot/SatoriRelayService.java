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
import net.minecraft.network.chat.HoverEvent;
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

    private enum DeliveryStatus {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }

    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new RelayThreadFactory());
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
    private long lastOutboundSentAtMillis;
    private ScheduledFuture<?> flushFuture;

    public synchronized void start(MinecraftServer server) {
        stop();
        if (!validateRequiredConfig()) {
            return;
        }
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
            this.lastOutboundSentAtMillis = 0L;
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

        String fullMessage = formatOutboundMinecraftMessage(cleanUser, cleanMessage);
        List<String> immediateBatch = null;
        long now = System.currentTimeMillis();
        long mergeWindowMillis = TimeUnit.SECONDS.toMillis(Config.mergeWindowSeconds());

        synchronized (this.bufferLock) {
            if (!this.outboundBuffer.isEmpty()) {
                this.outboundBuffer.add(fullMessage);
                scheduleBufferedFlushLocked(now, mergeWindowMillis);
                return;
            }

            boolean withinMergeWindow = this.lastOutboundSentAtMillis > 0L
                    && now - this.lastOutboundSentAtMillis < mergeWindowMillis;
            if (withinMergeWindow) {
                this.outboundBuffer.add(fullMessage);
                scheduleBufferedFlushLocked(now, mergeWindowMillis);
                return;
            }

            this.lastOutboundSentAtMillis = now;
            immediateBatch = List.of(fullMessage);
        }

        sendMergedMinecraftMessages(Objects.requireNonNull(immediateBatch));
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
            this.lastOutboundSentAtMillis = System.currentTimeMillis();
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
            SatoriBot.LOGGER.error(
                    "Invalid Satori configuration. satoriUrl={}",
                    Config.satoriUrl(),
                    ex);
            return;
        }

        List<String> targetGroupIds = Config.groupIds();
        String escapedContent = SatoriText.escapePlainText(String.join("\n", batch));
        List<CompletableFuture<DeliveryStatus>> deliveries = new ArrayList<>(targetGroupIds.size());
        for (String groupId : targetGroupIds) {
            deliveries.add(sendMessageCreate(endpoint, groupId, escapedContent));
        }

        CompletableFuture.allOf(deliveries.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, throwable) -> {
                    boolean anySuccess = false;
                    boolean anyTransientFailure = false;

                    for (CompletableFuture<DeliveryStatus> delivery : deliveries) {
                        DeliveryStatus status = delivery.getNow(DeliveryStatus.PERMANENT_FAILURE);
                        if (status == DeliveryStatus.SUCCESS) {
                            anySuccess = true;
                        }
                        if (status == DeliveryStatus.TRANSIENT_FAILURE) {
                            anyTransientFailure = true;
                        }
                    }

                    if (anyTransientFailure && !anySuccess) {
                        requeue(batch);
                    }
                });
    }

    private CompletableFuture<DeliveryStatus> sendMessageCreate(URI endpoint, String groupId, String escapedContent) {
        JsonObject payload = new JsonObject();
        payload.addProperty("channel_id", groupId);
        payload.addProperty("content", escapedContent);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Satori-Platform", this.loginPlatform)
                .header("Satori-User-ID", this.selfUserId)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));

        if (!Config.satoriToken().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + Config.satoriToken());
        }

        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        SatoriBot.LOGGER.error("Failed to forward Minecraft chat to Satori. groupId={}", groupId,
                                throwable);
                        return DeliveryStatus.TRANSIENT_FAILURE;
                    }

                    int status = response.statusCode();
                    if (status / 100 == 2) {
                        SatoriBot.LOGGER.debug("Forwarded Minecraft chat to Satori. groupId={}, status={}", groupId,
                                status);
                        return DeliveryStatus.SUCCESS;
                    }

                    SatoriBot.LOGGER.error(
                            "Satori message.create failed. endpoint={}, groupId={}, status={}, body={}",
                            endpoint,
                            groupId,
                            status,
                            response.body());
                    return (status >= 500 || status == 429)
                            ? DeliveryStatus.TRANSIENT_FAILURE
                            : DeliveryStatus.PERMANENT_FAILURE;
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
            scheduleBufferedFlushLocked(System.currentTimeMillis(),
                    TimeUnit.SECONDS.toMillis(Config.mergeWindowSeconds()));
        }
    }

    private void scheduleBufferedFlushLocked(long now, long mergeWindowMillis) {
        if (this.flushFuture != null && !this.flushFuture.isDone()) {
            return;
        }

        long dueAt = this.lastOutboundSentAtMillis > 0L
                ? this.lastOutboundSentAtMillis + mergeWindowMillis
                : now + mergeWindowMillis;
        long delayMillis = Math.max(0L, dueAt - now);
        this.flushFuture = this.scheduler.schedule(this::flushBufferedMessages, delayMillis, TimeUnit.MILLISECONDS);
    }

    private String formatOutboundMinecraftMessage(String username, String message) {
        String prefix = Config.prefix();
        StringBuilder builder = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            builder.append(prefix);
            if (!Character.isWhitespace(prefix.charAt(prefix.length() - 1))) {
                builder.append(' ');
            }
        }
        builder.append('<').append(username).append('>').append(' ').append(message);
        return builder.toString();
    }

    private boolean canSendHttpMessages() {
        return this.running
                && !Config.groupIds().isEmpty()
                && !Config.satoriToken().isEmpty()
                && !Config.satoriUrl().isEmpty()
                && this.loginPlatform != null
                && !this.loginPlatform.isBlank()
                && this.selfUserId != null
                && !this.selfUserId.isBlank();
    }

    private boolean validateRequiredConfig() {
        List<String> configuredGroupIds = Config.groupIds();
        boolean valid = true;
        if (configuredGroupIds.isEmpty()) {
            SatoriBot.LOGGER
                    .error("Satori relay disabled: config groupIds is empty. Please configure at least one group id.");
            valid = false;
        }
        if (Config.satoriToken().isEmpty()) {
            SatoriBot.LOGGER
                    .error("Satori relay disabled: config satoriToken is empty. Please configure a valid token.");
            valid = false;
        }
        if (!valid) {
            SatoriBot.LOGGER.error("Satori relay startup aborted due to invalid required configuration.");
            return false;
        }
        SatoriBot.LOGGER.info("Satori relay enabled. groupIds={}, satoriUrl={}", configuredGroupIds,
                Config.satoriUrl());
        return true;
    }

    private synchronized void connectWebSocket() {
        if (!this.running) {
            return;
        }

        URI wsUri;
        try {
            wsUri = URI.create(normalizeWsUrl(Config.satoriUrl()));
        } catch (IllegalArgumentException ex) {
            SatoriBot.LOGGER.error("Invalid Satori url in config: {}", Config.satoriUrl(), ex);
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

        List<String> configuredGroupIds = Config.groupIds();
        if (configuredGroupIds.isEmpty()) {
            return;
        }

        JsonObject channel = getAsObject(body, "channel");
        JsonObject guild = getAsObject(body, "guild");
        String channelId = channel == null ? "" : getAsString(channel, "id");
        String guildId = guild == null ? "" : getAsString(guild, "id");
        String matchedGroupId = findMatchedGroupId(configuredGroupIds, channelId, guildId);
        if (matchedGroupId.isEmpty()) {
            SatoriBot.LOGGER.debug(
                    "Ignoring Satori message-created outside configured groups. channelId={}, guildId={}, configuredGroupIds={}",
                    channelId,
                    guildId,
                    configuredGroupIds);
            return;
        }

        JsonObject message = getAsObject(body, "message");
        JsonObject member = getAsObject(body, "member");
        JsonObject user = getAsObject(body, "user");
        if (user == null && message != null) {
            user = getAsObject(message, "user");
        }
        if (message == null || user == null) {
            SatoriBot.LOGGER.debug(
                    "Ignoring Satori message-created without usable message/user payload. matchedGroupId={}, hasMessage={}, hasUser={}",
                    matchedGroupId,
                    message != null,
                    user != null);
            return;
        }

        String userId = getAsString(user, "id");
        if (userId.isEmpty()) {
            SatoriBot.LOGGER.debug("Ignoring Satori message-created with empty user id. matchedGroupId={}",
                    matchedGroupId);
            return;
        }
        if (userId.equals(this.selfUserId)) {
            return;
        }

        String displayName = firstNonBlank(
                member == null ? "" : getAsString(member, "nick"),
                getAsString(user, "nick"),
                getAsString(user, "name"),
                userId);
        String plainText = SatoriText.toPlainText(getAsString(message, "content"));
        if (plainText.isEmpty()) {
            SatoriBot.LOGGER.debug(
                    "Ignoring Satori message-created with empty parsed content. matchedGroupId={}, userId={}",
                    matchedGroupId, userId);
            return;
        }

        SatoriBot.LOGGER.debug("Relaying inbound Satori message. matchedGroupId={}, userId={}", matchedGroupId, userId);
        relayToMinecraft(displayName, userId, plainText, matchedGroupId);
    }

    private String findMatchedGroupId(List<String> configuredGroupIds, String channelId, String guildId) {
        if (!channelId.isEmpty() && configuredGroupIds.contains(channelId)) {
            return channelId;
        }
        if (!guildId.isEmpty() && configuredGroupIds.contains(guildId)) {
            return guildId;
        }
        return "";
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

    private void relayToMinecraft(String displayName, String userId, String plainText, String groupId) {
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
                        Objects.requireNonNull(buildInboundMessage(displayName, userId, line.trim(), groupId)),
                        false);
            }
        });
    }

    private MutableComponent buildInboundMessage(String displayName, String userId, String message, String groupId) {
        String safeDisplayName = Objects.requireNonNull(displayName);
        String safeUserId = Objects.requireNonNull(userId);
        String safeMessage = Objects.requireNonNull(message);
        String safeGroupId = Objects.requireNonNull(groupId);
        String sender = safeDisplayName + "(" + safeUserId + ")";
        String hoverText = "群" + safeGroupId;
        HoverEvent hoverEvent = new HoverEvent(
                Objects.requireNonNull(HoverEvent.Action.SHOW_TEXT),
                Objects.requireNonNull(Component.literal(hoverText)));

        return Objects.requireNonNull(Component.empty())
                .append(Objects.requireNonNull(Component.literal("<").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(
                        Component.literal(sender).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withHoverEvent(Objects.requireNonNull(hoverEvent)))))
                .append(Objects.requireNonNull(Component.literal("> ").withStyle(ChatFormatting.GRAY)))
                .append(Objects.requireNonNull(Component.literal(safeMessage).withStyle(ChatFormatting.WHITE)));
    }

    private URI buildMessageCreateUri() {
        URI apiBaseUri = resolveApiBaseUri();
        String path = apiBaseUri.getPath() == null ? "" : apiBaseUri.getPath();
        if (path.isEmpty()) {
            path = "/v1";
        }
        path = path.replaceAll("/+$", "") + "/message.create";

        try {
            return new URI(
                    apiBaseUri.getScheme(),
                    apiBaseUri.getUserInfo(),
                    apiBaseUri.getHost(),
                    apiBaseUri.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Unable to derive Satori HTTP API endpoint.", ex);
        }
    }

    private URI resolveApiBaseUri() {
        URI wsUri = URI.create(normalizeWsUrl(Config.satoriUrl()));
        String httpScheme = "wss".equalsIgnoreCase(wsUri.getScheme()) ? "https" : "http";
        String path = wsUri.getPath() == null ? "" : wsUri.getPath();
        if (path.endsWith("/events")) {
            path = path.substring(0, path.length() - "/events".length());
        }
        if (path.isEmpty()) {
            path = "/v1";
        }

        try {
            return new URI(httpScheme, wsUri.getUserInfo(), wsUri.getHost(), wsUri.getPort(), path, null, null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Unable to derive Satori HTTP API base endpoint.", ex);
        }
    }

    private String normalizeWsUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Satori url is blank.");
        }

        URI uri = URI.create(rawUrl.trim());
        String scheme = uri.getScheme();
        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Satori url must use ws:// or wss://.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Satori url is missing a host.");
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
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), path, uri.getQuery(),
                    uri.getFragment()).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid Satori url.", ex);
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
        this.pingFuture = this.scheduler.scheduleAtFixedRate(this::sendPing, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
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
