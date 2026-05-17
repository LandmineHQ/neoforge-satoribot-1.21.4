# Version source template

Copy `java/` to `src/versioned/<mcVer>/java/` when adding a new Minecraft
version, then adjust the files that touch changed Minecraft or NeoForge APIs.

The usual edit points are:

- `NeoForgeRelayConfig.java`: NeoForge config spec API.
- `NeoForgeMinecraftRelayBridge.java`: inbound chat component and hover event API.
- `SatoriBotClient.java`: client-only setup or config screen extension APIs.
- `NeoForgeVersionAdapter.java`: wire a different bridge/config implementation if a version needs one.

The shared NeoForge lifecycle, config registration, server event handling, and
message formatting live in `src/loader/neoforge/common/java`.
