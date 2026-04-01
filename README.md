# SatoriBot (NeoForge 1.21.4)

一个用于 `Minecraft 服务器聊天 <-> Satori` 双向转发的 NeoForge 模组。  
核心用途是把游戏内聊天转发到多个指定 QQ 群（Satori 侧的 `channel_id` 列表），并把群消息实时展示到游戏公屏。

## 功能概览

- 游戏内消息转发到 Satori（HTTP `message.create`）
- 群消息通过 Satori WebSocket 事件转发到 Minecraft 公屏
- 支持消息合并窗口，避免短时间高频刷屏
- 支持转发前缀 `prefix`
- 支持图文混排消息解析（图片/语音/视频/文件/表情等占位显示）
- 自动会话心跳（`PING/PONG`）与断线重连

## 转发行为

### Minecraft -> 群聊

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

## 运行要求

- Java 21+
- Minecraft `1.21.4`
- NeoForge `21.4.157`
- 可用的 Satori 服务端（提供 `ws(s)://.../v1/events` 与对应 HTTP API）

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

## 构建与开发运行

构建：

```bash
./gradlew build
```

产物目录：

```text
build/libs/
```

开发运行：

```bash
./gradlew runServer
./gradlew runClient
```

## GitHub Actions 工作流

- `build.yml`：通用构建检查（push / pull_request）
- `preview.yml`：构建 `main` 最新代码并更新单一 Preview Release
- `release.yml`：当推送 `vX.Y.Z` tag 时构建并发布正式 Release
- `reusable-build.yml`：复用构建逻辑（供上述流程调用）

## 协议文档

项目内文档：

- [`SATORI_DOC.md`](./SATORI_DOC.md)

官方文档：

- <https://satori.chat/zh-CN/protocol/events.html>
- <https://satori.chat/zh-CN/protocol/elements.html>
- <https://satori.chat/zh-CN/resources/message.html>
