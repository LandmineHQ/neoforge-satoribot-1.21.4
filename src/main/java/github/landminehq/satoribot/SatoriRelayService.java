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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

final class SatoriRelayService {
    private static final int OP_EVENT = 0;
    private static final int OP_PING = 1;
    private static final int OP_PONG = 2;
    private static final int OP_IDENTIFY = 3;
    private static final int OP_READY = 4;
    private static final long HEARTBEAT_SECONDS = 10L;
    private static final long RECONNECT_DELAY_SECONDS = 5L;
    private static final long INTER_GROUP_DELAY_MIN_SECONDS = 1L;
    private static final long INTER_GROUP_DELAY_MAX_SECONDS = 3L;

    private enum DeliveryStatus {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }

    private static final class DeliverySummary {
        private boolean anySuccess;
        private boolean anyTransientFailure;

        void record(DeliveryStatus status) {
            if (status == DeliveryStatus.SUCCESS) {
                this.anySuccess = true;
            }
            if (status == DeliveryStatus.TRANSIENT_FAILURE) {
                this.anyTransientFailure = true;
            }
        }
    }

    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new RelayThreadFactory());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final RelayConfig config;
    private final Logger logger;
    private final Object bufferLock = new Object();
    private final List<String> outboundBuffer = new ArrayList<>();

    private volatile MinecraftRelayBridge minecraftBridge;
    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile long lastSn;
    private volatile String loginPlatform;
    private volatile String selfUserId;
    private volatile ScheduledFuture<?> pingFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private long lastOutboundSentAtMillis;
    private ScheduledFuture<?> flushFuture;

    SatoriRelayService(RelayConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config);
        this.logger = Objects.requireNonNull(logger);
    }

    public synchronized void start(MinecraftRelayBridge minecraftBridge) {
        stop();
        if (!validateRequiredConfig()) {
            return;
        }
        this.minecraftBridge = Objects.requireNonNull(minecraftBridge);
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

        this.minecraftBridge = null;
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
        long mergeWindowMillis = TimeUnit.SECONDS.toMillis(this.config.mergeWindowSeconds());

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
            this.logger.error(
                    "Invalid Satori configuration. satoriUrl={}",
                    this.config.satoriUrl(),
                    ex);
            return;
        }

        List<String> targetGroupIds = this.config.groupIds();
        String escapedContent = SatoriText.escapePlainText(String.join("\n", batch));
        CompletableFuture<DeliverySummary> sequence = CompletableFuture.completedFuture(new DeliverySummary());
        for (int i = 0; i < targetGroupIds.size(); i++) {
            String groupId = targetGroupIds.get(i);
            boolean delayBeforeSend = i > 0;
            sequence = sequence.thenCompose(summary -> createInterGroupDelay(delayBeforeSend)
                    .thenCompose(ignored -> sendMessageCreate(endpoint, groupId, escapedContent))
                    .thenApply(status -> {
                        summary.record(status);
                        return summary;
                    }));
        }

        sequence.whenComplete((summary, throwable) -> {
            if (summary != null && summary.anyTransientFailure && !summary.anySuccess) {
                requeue(batch);
            }
        });
    }

    private CompletableFuture<Void> createInterGroupDelay(boolean enabled) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }

        long delaySeconds = ThreadLocalRandom.current()
                .nextLong(INTER_GROUP_DELAY_MIN_SECONDS, INTER_GROUP_DELAY_MAX_SECONDS + 1L);
        this.logger.debug("Delaying next Satori group send by {} seconds.", delaySeconds);
        return CompletableFuture.runAsync(
                () -> {
                },
                CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS, this.scheduler)
        );
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

        if (!this.config.satoriToken().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + this.config.satoriToken());
        }

        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        this.logger.error("Failed to forward Minecraft chat to Satori. groupId={}", groupId,
                                throwable);
                        return DeliveryStatus.TRANSIENT_FAILURE;
                    }

                    int status = response.statusCode();
                    if (status / 100 == 2) {
                        this.logger.debug("Forwarded Minecraft chat to Satori. groupId={}, status={}", groupId,
                                status);
                        return DeliveryStatus.SUCCESS;
                    }

                    this.logger.error(
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
                    TimeUnit.SECONDS.toMillis(this.config.mergeWindowSeconds()));
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
        String prefix = this.config.prefix();
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
                && !this.config.groupIds().isEmpty()
                && !this.config.satoriToken().isEmpty()
                && !this.config.satoriUrl().isEmpty()
                && this.loginPlatform != null
                && !this.loginPlatform.isBlank()
                && this.selfUserId != null
                && !this.selfUserId.isBlank();
    }

    private boolean validateRequiredConfig() {
        List<String> configuredGroupIds = this.config.groupIds();
        boolean valid = true;
        if (configuredGroupIds.isEmpty()) {
            this.logger
                    .error("Satori relay disabled: config groupIds is empty. Please configure at least one group id.");
            valid = false;
        }
        if (this.config.satoriToken().isEmpty()) {
            this.logger
                    .error("Satori relay disabled: config satoriToken is empty. Please configure a valid token.");
            valid = false;
        }
        if (!valid) {
            this.logger.error("Satori relay startup aborted due to invalid required configuration.");
            return false;
        }
        this.logger.info("Satori relay enabled. groupIds={}, satoriUrl={}", configuredGroupIds,
                this.config.satoriUrl());
        return true;
    }

    private synchronized void connectWebSocket() {
        if (!this.running) {
            return;
        }

        URI wsUri;
        try {
            wsUri = URI.create(normalizeWsUrl(this.config.satoriUrl()));
        } catch (IllegalArgumentException ex) {
            this.logger.error("Invalid Satori url in config: {}", this.config.satoriUrl(), ex);
            scheduleReconnect();
            return;
        }

        this.logger.info("Connecting to Satori websocket: {}", wsUri);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, new SatoriWebSocketListener())
                .whenComplete((socket, throwable) -> {
                    if (throwable != null) {
                        this.logger.error("Unable to connect to Satori websocket.", throwable);
                        scheduleReconnect();
                    }
                });
    }

    private void handleWebSocketPayload(String payload) {
        JsonObject packet;
        try {
            packet = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException ex) {
            this.logger.error("Invalid Satori websocket payload: {}", payload, ex);
            return;
        }

        int op = getAsInt(packet, "op", -1);
        JsonObject body = getAsObject(packet, "body");

        switch (op) {
            case OP_EVENT -> handleEvent(body);
            case OP_READY -> handleReady(body);
            case OP_PONG -> {
            }
            default -> this.logger.debug("Ignoring Satori opcode {}", op);
        }
    }

    private void handleReady(JsonObject body) {
        JsonArray logins = getAsArray(body, "logins");
        if (logins == null || logins.isEmpty()) {
            this.logger.warn("Received READY without login context.");
            return;
        }

        for (JsonElement element : logins) {
            if (element.isJsonObject() && updateLoginContext(element.getAsJsonObject())) {
                this.logger.info("Satori login ready: {} / {}", this.loginPlatform, this.selfUserId);
                return;
            }
        }

        this.logger.warn("Received READY but could not extract a valid login context.");
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

        List<String> configuredGroupIds = this.config.groupIds();
        if (configuredGroupIds.isEmpty()) {
            return;
        }

        JsonObject channel = getAsObject(body, "channel");
        JsonObject guild = getAsObject(body, "guild");
        String channelId = channel == null ? "" : getAsString(channel, "id");
        String guildId = guild == null ? "" : getAsString(guild, "id");
        String matchedGroupId = findMatchedGroupId(configuredGroupIds, channelId, guildId);
        if (matchedGroupId.isEmpty()) {
            this.logger.debug(
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
            this.logger.debug(
                    "Ignoring Satori message-created without usable message/user payload. matchedGroupId={}, hasMessage={}, hasUser={}",
                    matchedGroupId,
                    message != null,
                    user != null);
            return;
        }

        String userId = getAsString(user, "id");
        if (userId.isEmpty()) {
            this.logger.debug("Ignoring Satori message-created with empty user id. matchedGroupId={}",
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
            this.logger.debug(
                    "Ignoring Satori message-created with empty parsed content. matchedGroupId={}, userId={}",
                    matchedGroupId, userId);
            return;
        }

        this.logger.debug("Relaying inbound Satori message. matchedGroupId={}, userId={}", matchedGroupId, userId);
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
        MinecraftRelayBridge currentBridge = this.minecraftBridge;
        if (currentBridge == null) {
            return;
        }

        String[] lines = plainText.split("\\n");
        currentBridge.execute(() -> {
            if (this.minecraftBridge != currentBridge) {
                return;
            }
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                currentBridge.broadcastInboundMessage(displayName, userId, line.trim(), groupId);
            }
        });
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
        URI wsUri = URI.create(normalizeWsUrl(this.config.satoriUrl()));
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
        if (!this.config.satoriToken().isEmpty()) {
            body.addProperty("token", this.config.satoriToken());
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
                    this.logger.error("Failed to send websocket payload to Satori.", throwable);
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
                logger.warn("Satori websocket closed: {} {}", statusCode, reason);
                scheduleReconnect();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("Satori websocket error.", error);
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
