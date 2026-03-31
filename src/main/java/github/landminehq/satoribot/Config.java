package github.landminehq.satoribot;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> GROUP_ID = BUILDER
            .comment("Target QQ group/channel id. This value is sent as Satori message.create channel_id.")
            .define("groupId", "");

    public static final ModConfigSpec.IntValue MERGE_WINDOW_SECONDS = BUILDER
            .comment("Merge Minecraft chat messages for at least this many seconds before forwarding.")
            .defineInRange("mergeWindowSeconds", 5, 5, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> SATORI_TOKEN = BUILDER
            .comment("Satori bearer token used for websocket IDENTIFY and HTTP Authorization.")
            .define("satoriToken", "");

    public static final ModConfigSpec.ConfigValue<String> SATORI_WS_URL = BUILDER
            .comment("Satori websocket address. Accepts ws(s)://host/v1/events or a ws(s)://host/v1 base URL.")
            .define("satoriWsUrl", "ws://127.0.0.1:5600/v1/events");

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static String groupId() {
        return GROUP_ID.get().trim();
    }

    public static int mergeWindowSeconds() {
        return Math.max(5, MERGE_WINDOW_SECONDS.get());
    }

    public static String satoriToken() {
        return SATORI_TOKEN.get().trim();
    }

    public static String satoriWsUrl() {
        return SATORI_WS_URL.get().trim();
    }
}
