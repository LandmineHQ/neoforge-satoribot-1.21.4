# SatoriBot (NeoForge 多版本)

一个用于 `Minecraft 服务器聊天 <-> Satori` 双向转发的 NeoForge 模组。  
核心用途是把游戏内聊天转发到多个指定 QQ 群（Satori 侧的 `channel_id` 列表），并把群消息实时展示到游戏公屏。

## 功能概览

- 游戏内消息转发到 Satori（HTTP `message.create`）
- 玩家进入/退出服务器事件转发到 Satori
- 群消息通过 Satori WebSocket 事件转发到 Minecraft 公屏
- 支持 Satori 侧命令查询 Minecraft 在线玩家
- 支持消息合并窗口，避免短时间高频刷屏
- 支持转发前缀 `prefix`
- 支持图文混排消息解析（图片/语音/视频/文件/表情等占位显示）
- 自动会话心跳（`PING/PONG`）与断线重连

## 转发行为

### Minecraft -> 群聊

- 游戏内聊天、玩家进入、玩家退出都会作为 Minecraft 侧消息转发到 Satori
- 发送前先判断“当前时间”与“上次成功发送时间”
- 若小于 `mergeWindowSeconds`（最小 5 秒），消息进入缓冲队列
- 若大于等于窗口，立即发送
- 到达窗口后将队列消息合并发送

发送文本格式：

```text
<prefix> <玩家名> <消息内容>
```

### 群聊 -> Minecraft

- 仅处理匹配 `groupIds` 列表中的消息事件
- 不做合并，收到即转发
- 显示格式：

```text
<昵称(用户ID)> 消息内容
```

- 名字悬停提示：`群<group_id>`（当前消息命中的群 ID）

### Satori 命令

Satori 消息会先检查是否为命令；命令消息不会再转发到 Minecraft 公屏。

当前预设命令：

```text
!!list
!!ls
```

如果配置了 `prefix`，命令需要带上这个前缀。例如 `prefix = "mc"` 时：

```text
!!mclist
!!mcls
```

`list` / `ls` 会查询 Minecraft 当前在线玩家，并把结果回复到触发命令的 Satori 频道。

## 运行要求

- 默认构建目标：Minecraft `26.1.2`
- 默认 NeoForge：`26.1.2.59-beta`
- 默认 Java：`25`
- 其他版本目标按 `versionProperties/<version>.properties` 中的 `java_version` 配置
- 可用的 Satori 服务端（提供 `ws(s)://.../v1/events` 与对应 HTTP API）

具体 Minecraft / NeoForge / Java 版本由 `versionProperties/<version>.properties` 决定。

## 配置

首次运行后会生成配置文件：

```text
config/satoribot-common.toml
```

需要配置的字段：

- `groupIds`：目标群/频道 ID 列表（用于 `message.create.channel_id`）
- `prefix`：游戏内转发前缀，默认空字符串
- `mergeWindowSeconds`：合并窗口秒数，最小 5
- `satoriToken`：Satori 鉴权 token
- `satoriUrl`：Satori WS 地址，支持：
  - `ws://host/v1/events`
  - `ws://host/v1`
  - `wss://host/v1/events`
  - `wss://host/v1`

必填校验：

- `groupIds` 不能为空
- `satoriToken` 不能为空
- 若为空，模组会记录错误日志并中止中继功能启动（不会终止 Minecraft 服务器）

示例：

```toml
groupIds = ["1234567890", "2345678901"]
prefix = "[MC]"
mergeWindowSeconds = 5
satoriToken = "your-token"
satoriUrl = "ws://127.0.0.1:5600/v1/events"
```

## 多版本构建方式

本项目采用类似 Distant Horizons 的“单仓库 + 版本属性文件”维护方式：

```text
gradle.properties                 # 默认 mcVer 和模组基础信息
src/main/java/                    # 通用协议、抽象接口、版本无关逻辑
src/main/resources/               # 通用资源
src/loader/neoforge/common/java   # NeoForge loader 侧共享运行时
src/versioned/_template/java      # 新增版本时可复制的 Java 模板
versionProperties/26.1.2.properties
versionProperties/1.21.11.properties
versionProperties/1.21.4.properties
versionProperties/1.21.1.properties
versionProperties/<version>.properties
versionProperties/_template.properties
src/versioned/<version>/java      # 版本专属 Java 适配代码
src/versioned/<version>/resources # 可选：版本专属资源
```

默认目标由 `gradle.properties` 中的 `mcVer` 指定：

```properties
mcVer=26.1.2
```

构建默认版本：

```bash
./gradlew build
```

构建指定版本：

```bash
./gradlew build -PmcVer=<version>
```

PowerShell 中建议给 `-P` 参数加引号，避免 bat 参数被拆分：

```powershell
.\gradlew.bat build "-PmcVer=<version>"
```

CI 会从 `versionProperties/*.properties` 自动生成版本矩阵，并行构建每个目标。日常本地验证优先只构建当前修改影响到的目标版本，不在 Gradle 中维护“一次性顺序构建全部版本”的任务。

查看当前 Gradle 调用选中的版本：

```bash
./gradlew printSelectedMinecraftVersion
```

产物目录按 MC 版本隔离，例如：

```text
build/<version>/libs/
```

### 分层约定

- `src/main/java`：放 Satori 协议、HTTP/WebSocket、中继缓冲、文本解析，以及 `RelayConfig` / `MinecraftRelayBridge` 这类纯抽象。
- `src/loader/neoforge/common/java`：放明确属于 NeoForge loader 的共享运行时，例如 `NeoForgeSatoriBotRuntime`、`NeoForgeRuntimeAdapter` 和 Minecraft 组件广播辅助。
- `src/versioned/<version>/java`：放该版本真正需要实现或覆盖的适配代码，例如 `NeoForgeVersionAdapter`、`NeoForgeRelayConfig`、`@Mod` 入口、HoverEvent shim、客户端配置入口。
- 抽象层不能直接 import `net.minecraft.*` 或 `net.neoforged.*`。如果代码依赖 NeoForge/Minecraft，但能跨多个 NeoForge 版本共享，放到 `src/loader/neoforge/common`，不要放到抽象 common 层。
- NeoForge config spec 也属于版本 API 表面。即使多个版本暂时写法相同，也优先放在 `src/versioned/<version>/java/NeoForgeRelayConfig.java`，由 `NeoForgeVersionAdapter` 暴露给 loader runtime。
- 当某个 MC 版本 API 变化时，优先只修改对应的 `src/versioned/<version>`；只有确认多个 NeoForge 版本能共享时，才上移到 `src/loader/neoforge/common`。

新增一个 Minecraft 版本时：

1. 复制 `versionProperties/_template.properties` 为 `versionProperties/<version>.properties`
2. 填入该版本对应的 `minecraft_version`、`neo_version`、`java_version` 等字段
3. 复制 `src/versioned/_template/java` 到 `src/versioned/<version>/java`，或从最接近的已有版本复制
4. 让该版本的 `NeoForgeVersionAdapter` 实现 `NeoForgeRuntimeAdapter`，并按需调整 HoverEvent、客户端配置入口等版本差异
5. 如果该 NeoForge 线没有 `clientData` run type，在版本属性中设置 `supports_client_data_run=false`
6. 执行 `./gradlew build -PmcVer=<version>` 验证

## 构建与开发运行

构建：

```bash
./gradlew build
```

产物目录：

```text
build/<version>/libs/
```

本地开发测试采用类似 Distant Horizons 的 Gradle 任务方式，不维护 `.vscode/launch.json` 作为项目启动入口。VS Code 或其他 IDE 可以导入 Gradle 项目后自行生成本地运行配置，但项目内约定的测试入口是下面这些命令。

运行默认版本：

```bash
./gradlew runServer
./gradlew runClient
```

运行指定版本：

```powershell
.\gradlew.bat runServer "-PmcVer=<version>"
.\gradlew.bat runClient "-PmcVer=<version>"
```

常用运行目录：

```text
runs/server
runs/client
```

## GitHub Actions 工作流

- `build.yml`：在 pull request 和非 `main` push 中自动发现版本矩阵，并行构建每个目标版本
- `preview.yml`：在 `main` push / 手动触发时并行构建版本矩阵，汇总产物后更新单一 Preview Release
- `release.yml`：当推送 `vX.Y.Z` tag 时并行构建版本矩阵，汇总产物后发布正式 Release
- `reusable-build.yml`：单版本构建子 workflow（由矩阵任务按 `mc_version` 调用）

## 协议文档

项目内文档：

- [`SATORI_DOC.md`](./SATORI_DOC.md)

官方文档：

- <https://satori.chat/zh-CN/protocol/events.html>
- <https://satori.chat/zh-CN/protocol/elements.html>
- <https://satori.chat/zh-CN/resources/message.html>
