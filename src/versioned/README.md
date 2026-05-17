# Version-specific source layout

Gradle includes `src/versioned/common/java`, `src/versioned/common/resources`,
`src/versioned/<mcVer>/java`, and `src/versioned/<mcVer>/resources` for the
selected Minecraft version.

Examples:

```text
src/versioned/common/java/
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

Use `src/versioned/common/java` for NeoForge/Minecraft code that is shared by
all supported selected versions:

- `NeoForgeSatoriBotAdapter`: interface each version implements.
- `AbstractNeoForgeSatoriBot`: common config registration and server event lifecycle.
- `AbstractNeoForgeMinecraftRelayBridge`: common inbound chat formatting.
- `NeoForgeRelayConfig`: shared config spec while the NeoForge config API remains compatible.

Use `src/versioned/<mcVer>/java` for concrete Minecraft / NeoForge implementations:

- `@Mod` entrypoints
- `NeoForgeVersionAdapter` implementations
- version-specific chat/component and hover-event shims
- client-only setup or config screen registration
- any API compatibility shim needed by one Minecraft version

When adding a version, copy `src/versioned/_template/java` to
`src/versioned/<mcVer>/java` and then patch only the APIs that changed for that
Minecraft / NeoForge target. If the new version is closer to an existing target,
copy that target instead.
