# Version-specific source layout

Gradle includes `src/versioned/<mcVer>/java` and `src/versioned/<mcVer>/resources`
for the selected Minecraft version.

Examples:

```text
src/versioned/1.21.4/java/
src/versioned/1.21.4/resources/
src/versioned/1.20.1/java/
src/versioned/1.20.1/resources/
```

Keep Satori protocol and other loader-independent code in `src/main/java`.
That common layer should depend on small interfaces such as `RelayConfig` and
`MinecraftRelayBridge` instead of importing Minecraft or NeoForge APIs directly.

Use these folders for concrete Minecraft / NeoForge implementations:

- `@Mod` entrypoints
- NeoForge config specs
- chat event listeners
- Minecraft chat/component broadcasting
- any API compatibility shim needed by one Minecraft version
