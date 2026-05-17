# Project Agent Notes

## Project Purpose

SatoriBot is a NeoForge Minecraft mod that bridges Minecraft server chat and Satori-compatible message channels.

- Minecraft chat is forwarded to configured Satori `channel_id` targets through HTTP `message.create`.
- Satori `message-created` events are received through WebSocket and broadcast to the Minecraft public chat.
- The relay supports message merge windows, optional outbound prefixes, Satori element-to-text parsing, heartbeat, and reconnect behavior.

## Repository Structure

- `src/main/java/github/landminehq/satoribot/`
  - Common, version-independent Java code.
  - Keep Satori protocol handling, HTTP/WebSocket relay logic, buffering, text parsing, and small abstraction interfaces here.
  - Important files:
    - `SatoriRelayService.java`: common relay runtime.
    - `SatoriText.java`: Satori element text conversion and escaping.
    - `RelayConfig.java`: common config abstraction.
    - `MinecraftRelayBridge.java`: common Minecraft chat bridge abstraction.
- `src/main/resources/`
  - Common resources shared by all supported Minecraft versions.
  - `META-INF/neoforge.mods.toml` uses Gradle property expansion.
- `src/versioned/<mcVer>/java/`
  - Version-specific Minecraft / NeoForge implementation code.
  - Put `@Mod` entrypoints, `NeoForgeVersionAdapter` implementations, client setup, chat component shims, and version API shims here.
  - Each version must provide a `NeoForgeVersionAdapter` implementing `NeoForgeSatoriBotAdapter`.
- `src/versioned/common/java/`
  - NeoForge / Minecraft-facing code shared by every selected version.
  - Important files:
    - `NeoForgeSatoriBotAdapter.java`: version adapter interface.
    - `AbstractNeoForgeSatoriBot.java`: shared config registration, server event lifecycle, and relay service wiring.
    - `AbstractNeoForgeMinecraftRelayBridge.java`: shared inbound chat component formatting.
    - `NeoForgeRelayConfig.java`: shared NeoForge config spec while the API remains compatible.
- `src/versioned/_template/java/`
  - Copyable Java source template for adding another Minecraft version.
  - Prefer copying the closest existing version when the target API is closer to it than to the template.
- `src/versioned/<mcVer>/resources/`
  - Optional version-specific resources for the selected Minecraft version.
- `versionProperties/`
  - One properties file per supported Minecraft version.
  - Current targets:
    - `26.1.2.properties`: default target, NeoForge `26.1.2.59-beta`, Java `25`.
    - `1.21.4.properties`: legacy target, NeoForge `21.4.157`, Java `21`.
  - Use `_template.properties` when adding another Minecraft version.
- `build.gradle`
  - Reads `mcVer`, loads `versionProperties/<mcVer>.properties`, adds `src/versioned/common` plus the selected `src/versioned/<mcVer>` source set, and writes outputs to `build/<mcVer>/`.
  - `buildAllVersions` runs supported versions sequentially through the Gradle wrapper to avoid NeoGradle parallel build conflicts.
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
.\gradlew.bat build --no-daemon "-PmcVer=1.21.4"
.\gradlew.bat buildAllVersions --no-daemon
```

PowerShell users should quote `-PmcVer=...` arguments when invoking `gradlew.bat`.

Local testing follows the Distant Horizons-style Gradle task workflow. Do not maintain `.vscode/launch.json` as the project source of truth; `.vscode/` is local IDE state and is ignored by Git.

```powershell
.\gradlew.bat runServer --no-daemon
.\gradlew.bat runClient --no-daemon
.\gradlew.bat runServer --no-daemon "-PmcVer=26.1.2"
.\gradlew.bat runClient --no-daemon "-PmcVer=26.1.2"
.\gradlew.bat runServer --no-daemon "-PmcVer=1.21.4"
.\gradlew.bat runClient --no-daemon "-PmcVer=1.21.4"
```

The run directories are `runs/server` and `runs/client`. IDE launch configurations may be regenerated locally from Gradle, but avoid committing or hand-maintaining them.

Expected jar outputs:

```text
build/<mcVer>/libs/satoribot-neoforge-<mcVer>-<mod_version>.jar
```

## Multi-Version Maintenance Rules

- Keep common behavior in `src/main/java`.
- Keep shared NeoForge / Minecraft wiring in `src/versioned/common/java`.
- Keep concrete version-specific Minecraft / NeoForge API usage in `src/versioned/<mcVer>/java`.
- When adding a Minecraft version:
  1. Copy `versionProperties/_template.properties` to `versionProperties/<mcVer>.properties`.
  2. Fill `minecraft_version`, `minecraft_version_range`, `neo_version`, `loader_version_range`, and `java_version`.
  3. Copy `src/versioned/_template/java` or the closest existing version directory to `src/versioned/<mcVer>/java`.
  4. Implement or adjust `NeoForgeVersionAdapter`, `NeoForgeMinecraftRelayBridge`, `SatoriBot`, and `SatoriBotClient` for the target version.
  5. Run `.\gradlew.bat build --no-daemon "-PmcVer=<mcVer>"`.
  6. Run `.\gradlew.bat buildAllVersions --no-daemon` when the change can affect shared behavior.
- If a version API changes, patch only that version directory first. Move code into `src/versioned/common` only when it compiles cleanly for every supported selected version and reduces real duplication.
- For NeoForge 26.1.2, `HoverEvent` text hover uses `new HoverEvent.ShowText(...)`; older 1.21.4 code uses the older `new HoverEvent(...)` form.
- For NeoForge 26.1.2, the old built-in `net.neoforged.neoforge.client.gui.ConfigurationScreen` / `IConfigScreenFactory` API is not available in the NeoForge jar used by this project. Keep `SatoriBotClient` present for client lifecycle wiring, but do not copy the 1.21.4 config screen registration unless a replacement API or dependency is added.

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
