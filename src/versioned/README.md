# Version-specific source layout

Gradle includes `src/loader/neoforge/common/java`,
`src/loader/neoforge/common/resources`, `src/versioned/<mcVer>/java`, and
`src/versioned/<mcVer>/resources` for the selected Minecraft version.

Examples:

```text
src/loader/neoforge/common/java/
src/versioned/_template/java/
src/versioned/1.21.11/java/
src/versioned/1.21.4/java/
src/versioned/1.21.4/resources/
src/versioned/1.20.1/java/
src/versioned/1.20.1/resources/
```

Keep Satori protocol and other loader-independent code in `src/main/java`.
That common layer should depend on small interfaces such as `RelayConfig` and
`MinecraftRelayBridge` instead of importing Minecraft or NeoForge APIs directly.

Do not put Minecraft or NeoForge imports in an abstract common layer. The pure
common layer is `src/main/java` and should stay behind small interfaces such as
`RelayConfig` and `MinecraftRelayBridge`.

Use `src/loader/neoforge/common/java` for NeoForge/Minecraft code that is shared
by all supported selected NeoForge versions:

- `NeoForgeRuntimeAdapter`: interface each version implements for the NeoForge runtime.
- `NeoForgeSatoriBotRuntime`: common config registration and server event lifecycle.
- `NeoForgeMinecraftRelayBridgeSupport`: common inbound chat formatting.
- `NeoForgeRelayConfig`: shared config spec while the NeoForge config API remains compatible.

Use `src/versioned/<mcVer>/java` for concrete Minecraft / NeoForge implementations:

- `@Mod` entrypoints
- `NeoForgeVersionAdapter` implementations
- version-specific chat/component and hover-event shims
- client-only setup or config screen registration
- any API compatibility shim needed by one Minecraft version

When adding a NeoForge version, copy `src/versioned/_template/java` to
`src/versioned/<mcVer>/java` and then patch only the APIs that changed for that
Minecraft / NeoForge target. If the new version is closer to an existing target,
copy that target instead.
