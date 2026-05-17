# NeoForge loader layer

This folder contains implementation shared by selected NeoForge Minecraft
versions. It may import `net.neoforged.*` and `net.minecraft.*` because it is
explicitly loader-specific.

Keep pure protocol and abstraction code in `src/main/java`. Do not use this
folder as a general abstract common layer for other loaders such as legacy
Forge.

Do not define version-sensitive `ModConfigSpec` values here. Each selected
Minecraft version owns its `NeoForgeRelayConfig` under `src/versioned/<mcVer>`.
