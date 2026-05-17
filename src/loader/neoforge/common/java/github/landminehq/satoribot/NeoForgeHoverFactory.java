package github.landminehq.satoribot;

import net.minecraft.network.chat.HoverEvent;

interface NeoForgeHoverFactory {
    HoverEvent createGroupHover(String groupId);
}
