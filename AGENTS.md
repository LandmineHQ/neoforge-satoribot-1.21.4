# Project Agent Notes

## Project Purpose

SatoriBot is a NeoForge Minecraft mod that bridges Minecraft server chat and Satori-compatible message channels.

- Minecraft chat is forwarded to configured Satori `channel_id` targets through HTTP `message.create`.
- Minecraft player join/leave events are forwarded to configured Satori targets as system messages.
- Satori `message-created` events are received through WebSocket and broadcast to the Minecraft public chat.
- Prefix-scoped Satori commands are intercepted before Minecraft broadcast; `!!list` / `!!ls` or `!!<prefix>list` / `!!<prefix>ls` replies with the online player list.
- The relay supports message merge windows, optional outbound prefixes, Satori element-to-text parsing, heartbeat, and reconnect behavior.

## Repository Structure

- `src/main/java/github/landminehq/satoribot/`
  - Common, version-independent Java code.
  - Keep Satori protocol handling, HTTP/WebSocket relay logic, buffering, text parsing, and small abstraction interfaces here.
  - Important files:
    - `SatoriRelayService.java`: common relay runtime.
    - `SatoriText.java`: Satori element text conversion and escaping.
    - `RelayConfig.java`: common config abstraction.
    - `MinecraftRelayBridge.java`: common Minecraft chat and query bridge abstraction.
- `src/main/resources/`
  - Common resources shared by all supported Minecraft versions.
  - `META-INF/neoforge.mods.toml` uses Gradle property expansion.
- `src/versioned/<mcVer>/java/`
  - Version-specific Minecraft / NeoForge implementation code.
  - Put `@Mod` entrypoints, `NeoForgeVersionAdapter` implementations, `NeoForgeRelayConfig` config specs, client setup, chat component shims, and version API shims here.
  - Each version must provide a `NeoForgeVersionAdapter` implementing `NeoForgeRuntimeAdapter`.
- `src/loader/neoforge/common/java/`
  - NeoForge / Minecraft-facing code shared by every selected NeoForge version.
  - This is loader-specific shared implementation, not the pure abstraction layer.
  - Important files:
    - `NeoForgeRuntimeAdapter.java`: loader runtime adapter interface implemented by each selected version.
    - `NeoForgeSatoriBotRuntime.java`: shared config registration, server/chat/player event lifecycle, and relay service wiring.
    - `NeoForgeMinecraftRelayBridgeSupport.java`: shared inbound chat component formatting.
- `src/versioned/_template/java/`
  - Copyable Java source template for adding another Minecraft version.
  - Prefer copying the closest existing version when the target API is closer to it than to the template.
- `src/versioned/<mcVer>/resources/`
  - Optional version-specific resources for the selected Minecraft version.
- `versionProperties/`
  - One properties file per supported Minecraft version.
  - Current targets:
    - `26.1.2.properties`: default target, NeoForge `26.1.2.0-beta`, Java `25`.
    - `1.21.11.properties`: NeoForge `21.11.0-beta`, Java `21`.
    - `1.21.4.properties`: legacy target, NeoForge `21.4.0-beta`, Java `21`.
    - `1.21.1.properties`: legacy target, NeoForge `21.1.1`, Java `21`.
  - Use `_template.properties` when adding another Minecraft version.
- `build.gradle`
  - Reads `mcVer`, loads `versionProperties/<mcVer>.properties`, adds `src/loader/neoforge/common` plus the selected `src/versioned/<mcVer>` source set, and writes outputs to `build/<mcVer>/`.
  - `buildAllVersions` runs supported versions sequentially through the Gradle wrapper to avoid NeoGradle parallel build conflicts, but it is expensive and should only be run when explicitly requested or when a broad shared change truly requires it.
- `.github/workflows/`
  - CI, preview, and release workflows build all declared `versionProperties/*.properties` targets.
  - Uploaded artifacts keep their versioned build paths, so release and preview jobs should publish jars with recursive globs such as `dist/**/*.jar`.

## Build And Validation

Default build target is configured in `gradle.properties`:

```properties
mcVer=26.1.2
```

Common commands:

```powershell
.\gradlew.bat printSelectedMinecraftVersion --no-daemon
.\gradlew.bat build --no-daemon
.\gradlew.bat build --no-daemon "-PmcVer=26.1.2"
.\gradlew.bat build --no-daemon "-PmcVer=1.21.11"
.\gradlew.bat build --no-daemon "-PmcVer=1.21.4"
.\gradlew.bat build --no-daemon "-PmcVer=1.21.1"
.\gradlew.bat buildAllVersions --no-daemon
```

PowerShell users should quote `-PmcVer=...` arguments when invoking `gradlew.bat`.

Prefer building only the Minecraft version targets affected by the current change. Do not run `buildAllVersions` by default because it is slow and resource-intensive; reserve it for explicit user requests or changes that cannot be validated safely with a smaller target set.

Local testing follows the Distant Horizons-style Gradle task workflow. Do not maintain `.vscode/launch.json` as the project source of truth; `.vscode/` is local IDE state and is ignored by Git.

```powershell
.\gradlew.bat runServer --no-daemon
.\gradlew.bat runClient --no-daemon
.\gradlew.bat runServer --no-daemon "-PmcVer=26.1.2"
.\gradlew.bat runClient --no-daemon "-PmcVer=26.1.2"
.\gradlew.bat runServer --no-daemon "-PmcVer=1.21.11"
.\gradlew.bat runClient --no-daemon "-PmcVer=1.21.11"
.\gradlew.bat runServer --no-daemon "-PmcVer=1.21.4"
.\gradlew.bat runClient --no-daemon "-PmcVer=1.21.4"
.\gradlew.bat runServer --no-daemon "-PmcVer=1.21.1"
.\gradlew.bat runClient --no-daemon "-PmcVer=1.21.1"
```

The run directories are `runs/server` and `runs/client`. IDE launch configurations may be regenerated locally from Gradle, but avoid committing or hand-maintaining them.

Expected jar outputs:

```text
build/<mcVer>/libs/satoribot-neoforge-<mcVer>-<mod_version>.jar
```

## Multi-Version Maintenance Rules

- Keep common behavior in `src/main/java`.
- Keep pure abstraction layers free of `net.minecraft.*` and `net.neoforged.*` imports.
- Keep shared NeoForge / Minecraft wiring in `src/loader/neoforge/common/java`.
- Keep concrete version-specific Minecraft / NeoForge API usage in `src/versioned/<mcVer>/java`.
- Keep NeoForge config specs in `src/versioned/<mcVer>/java/NeoForgeRelayConfig.java`; loader common may register a spec but must not define it.
- When adding a Minecraft version:
  1. Copy `versionProperties/_template.properties` to `versionProperties/<mcVer>.properties`.
  2. Fill `minecraft_version`, `minecraft_version_range`, `neo_version`, `loader_version_range`, and `java_version`.
  3. Copy `src/versioned/_template/java` or the closest existing version directory to `src/versioned/<mcVer>/java`.
  4. Implement or adjust `NeoForgeVersionAdapter`, `NeoForgeRelayConfig`, `NeoForgeMinecraftRelayBridge`, `SatoriBot`, and `SatoriBotClient` for the target version.
  5. Run `.\gradlew.bat build --no-daemon "-PmcVer=<mcVer>"`.
  6. Only run additional affected version targets when the change touches shared behavior; do not run the full matrix unless explicitly requested or truly necessary.
- If a version API changes, patch only that version directory first. Move code into `src/loader/neoforge/common` only when it compiles cleanly for every supported selected NeoForge version and reduces real duplication.
- If an older NeoForge line does not expose the `clientData` run type, set `supports_client_data_run=false` in its `versionProperties/<mcVer>.properties` file instead of removing the shared run configuration for newer versions.
- For NeoForge 1.21.11 and 26.1.2, `HoverEvent` text hover uses `new HoverEvent.ShowText(...)`; older 1.21.1 and 1.21.4 code use the older `new HoverEvent(...)` form.
- For NeoForge 1.21.11 and 26.1.2, the old built-in `net.neoforged.neoforge.client.gui.ConfigurationScreen` / `IConfigScreenFactory` API is not used by this project. Keep `SatoriBotClient` present for client lifecycle wiring, but do not copy the 1.21.1 / 1.21.4 config screen registration unless a replacement API or dependency is added.

## Documentation And Agent Maintenance

- Before every code or content change, decide whether this `AGENTS.md` needs an update.
- Update this file when changing:
  - Project structure.
  - Supported Minecraft / NeoForge / Java versions.
  - Build commands or CI workflow behavior.
  - Multi-version architecture boundaries.
  - Release or commit workflow.
  - Persistent agent instructions for this repository.
- Do not update this file for trivial implementation fixes that do not change workflow, architecture, or project conventions.

## Git Workflow

- After every repository modification, create a git commit automatically unless the user explicitly says not to commit.
- Use Conventional Commit messages, for example:
  - `feat: add 26.1.2 version target`
  - `fix: adapt hover events for 26.1.2`
  - `refactor: split relay bridge by minecraft version`
  - `docs: add project agent notes`
  - `build: add version property based builds`
  - `ci: build all declared minecraft targets`
- Before committing:
  - Check `git status --short`.
  - Run the relevant validation command when practical.
  - Include `AGENTS.md` in the commit if the change affects these instructions.
- This repository may require safe-directory git commands from the sandbox:

```powershell
git -c safe.directory=C:/Users/z1216/Desktop/satoribot-template-1.21.4 status --short
```

## Windows And Encoding

- When reading text files with PowerShell, always specify UTF-8 explicitly, for example:

```powershell
Get-Content -Encoding UTF8 README.md
```

- Prefer `rg` / `rg --files` for searching.
