package github.landminehq.satoribot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.neoforged.neoforge.common.ModConfigSpec;

final class NeoForgeRelayConfig implements RelayConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    @SuppressWarnings({"deprecation", "null"})
    static final ModConfigSpec.ConfigValue<List<? extends Object>> GROUP_IDS = BUILDER
            .comment(
                    "目标 QQ 群/频道 ID 列表。Minecraft 消息会转发到列表内所有群，入站事件也会按该列表过滤。",
                    "Target QQ group/channel id list. Outbound Minecraft messages are sent to all ids in this list; inbound events are filtered by this list."
            )
            .defineListAllowEmpty(
                    List.of("groupIds"),
                    () -> Objects.requireNonNull(List.<Object>of()),
                    value -> value instanceof String || value instanceof Number
            );

    static final ModConfigSpec.ConfigValue<String> PREFIX = BUILDER
            .comment(
                    "从 Minecraft 转发到群聊时附加在消息前面的前缀。默认空字符串。",
                    "Optional prefix added before messages forwarded from Minecraft to Satori. Default is empty."
            )
            .define("prefix", "");

    static final ModConfigSpec.IntValue MERGE_WINDOW_SECONDS = BUILDER
            .comment(
                    "Minecraft 消息合并转发的时间窗口，最小 5 秒。",
                    "Merge Minecraft chat messages for at least this many seconds before forwarding."
            )
            .defineInRange("mergeWindowSeconds", 5, 5, Integer.MAX_VALUE);

    static final ModConfigSpec.ConfigValue<String> SATORI_TOKEN = BUILDER
            .comment(
                    "Satori 鉴权 token，用于 WebSocket IDENTIFY 和 HTTP Authorization。",
                    "Satori bearer token used for websocket IDENTIFY and HTTP Authorization."
            )
            .define("satoriToken", "");

    static final ModConfigSpec.ConfigValue<String> SATORI_URL = BUILDER
            .comment(
                    "Satori 基础地址。支持填写 ws(s)://host/v1/events 或 ws(s)://host/v1，HTTP API 地址会自动推导。",
                    "Satori base URL. Accepts ws(s)://host/v1/events or ws(s)://host/v1 and derives HTTP API automatically."
            )
            .define("satoriUrl", "ws://127.0.0.1:5600/v1/events");

    static final ModConfigSpec SPEC = BUILDER.build();

    @Override
    public List<String> groupIds() {
        List<? extends Object> configured = GROUP_IDS.get();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (Object raw : configured) {
            if (raw == null) {
                continue;
            }
            String trimmed = String.valueOf(raw).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            deduplicated.add(trimmed);
        }
        return deduplicated.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(deduplicated));
    }

    @Override
    public String prefix() {
        return PREFIX.get();
    }

    @Override
    public int mergeWindowSeconds() {
        return Math.max(5, MERGE_WINDOW_SECONDS.get());
    }

    @Override
    public String satoriToken() {
        return SATORI_TOKEN.get().trim();
    }

    @Override
    public String satoriUrl() {
        return SATORI_URL.get().trim();
    }
}
