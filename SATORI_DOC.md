# Satori 接入说明（本项目）

本文只保留当前 `Minecraft <-> Satori` 转发模组真正需要的协议内容，并强调：

- 字段名以 Satori 官方文档为准
- 不使用“猜测字段”
- WebSocket 用于收事件，HTTP 用于发消息

## 1. 本项目最小协议集合

### 1.1 WebSocket（事件流）

- 地址：`ws(s)://<host>/v1/events`
- 关键 opcode：
- `0 EVENT`
- `1 PING`
- `2 PONG`
- `3 IDENTIFY`
- `4 READY`

连接流程：

1. 建立 WS 连接
2. 10 秒内发送 `IDENTIFY`
3. 周期发送 `PING`（建议 10 秒）
4. 接收 `READY` 获取登录上下文
5. 接收 `EVENT`（重点处理 `message-created`）

### 1.2 HTTP（发送消息）

- 接口：`POST /v1/message.create`
- 本项目实际请求体最小字段：
- `channel_id`
- `content`

请求头：

- `Content-Type: application/json`
- `Satori-Platform: <platform>`
- `Satori-User-ID: <self_id>`
- `Authorization: Bearer <token>`（有 token 时）

说明：本项目代码使用 `HTTP/1.1` 发送，避免部分网关在 HTTP 版本协商上的兼容问题。

## 2. 本项目配置与地址推导

配置项：

- `groupId`
- `prefix`
- `mergeWindowSeconds`（最小 5）
- `satoriToken`
- `satoriUrl`

`satoriUrl` 支持：

- `ws://host/v1/events`
- `ws://host/v1`
- `wss://host/v1/events`
- `wss://host/v1`

推导规则：

- WS：自动规范到 `/v1/events`
- HTTP：由 WS 推导
- `ws -> http`
- `wss -> https`
- 最终发送端点为 `<api-base>/message.create`

## 3. 字段使用规范（避免猜测）

下面是本项目当前使用且已按官方定义收敛的字段。

### 3.1 事件与资源字段

- 事件类型：`body.type == "message-created"`
- 会话序号：`body.sn`
- 登录上下文：
- `login.platform`
- `login.user.id`
- 路由过滤：
- `channel.id`
- `guild.id`
- 发送者：
- `user.id`
- `user.name`
- `user.nick`
- 群成员昵称（若存在）：
- `member.nick`
- 消息正文：
- `message.content`

### 3.2 message.create 字段

- `channel_id`
- `content`
- `referrer`（仅在“被动回复/引用上下文”场景需要）

## 4. 本项目消息解析规则（图文混排）

入站 `message.content` 是 Satori 消息元素编码，不应直接“仅保留纯文本并丢弃非文字”。

当前转义策略：

- 图片：`[图片]`
- 语音：`[语音]`
- 视频：`[视频]`
- 文件：`[文件]`

并保留可读文本顺序，例如：

```text
<img src="..." /> 这张图片真好看 <at id="123" name="Alice" />
```

转为：

```text
[图片] 这张图片真好看 @Alice
```

## 5. 标准元素属性（本项目实际实现）

以下为本项目当前已实现并按文档字段收敛的元素。

### 5.1 `at`

使用字段：

- `type`（`all` / `here`）
- `name`
- `role`
- `id`

显示优先级：

1. `type=all` -> `@全体成员`
2. `type=here` -> `@在线成员`
3. `name` -> `@<name>`
4. `role` -> `@身份组:<role>`
5. `id` -> `@<id>`
6. 否则 -> `[提及]`

### 5.2 `sharp`

使用字段：

- `name`
- `id`

显示：

- 优先 `#<name>`
- 否则 `#<id>`
- 否则 `[频道]`

### 5.3 `author`

使用字段：

- `name`
- `id`

显示：

- 优先 `[作者:<name>]`
- 否则 `[作者:<id>]`
- 否则 `[作者]`

### 5.4 `button`

使用字段：

- `text`
- `type`
- `link`

显示：

- 优先 `[按钮:<text>]`
- 否则 `[按钮链接:<link>]`
- 否则 `[按钮类型:<type>]`
- 否则 `[按钮]`

### 5.5 `a`

使用字段：

- `href`

自闭合链接元素会输出链接文本。

## 6. 入站显示名规则（本项目）

QQ群消息转 Minecraft 公屏时，发送者名优先级：

1. `member.nick`
2. `user.nick`
3. `user.name`
4. `user.id`

显示格式：

```text
<显示名(用户ID)> 消息内容
```

hover 文本：

```text
群<group_id>
```

## 7. 已知容易出错的点

1. 只配 `token`，但未从 `READY` 或事件 `login` 中获取 `platform/self_id` 就调用 HTTP。
2. 把 `groupId` 当成固定 `guild.id`，但实际平台发消息仍以 `channel_id` 为准。
3. WS 连接后未按时发送 `IDENTIFY`。
4. 不做 `PING/PONG` 导致长连接被动断开后未及时恢复。
5. 混淆 `event.sn` 与其他对象字段。
6. 把非标准 `internal/*` 当成标准公共接口。

## 8. 官方文档

- 协议总览：`https://satori.chat/zh-CN/protocol/`
- WebSocket 事件：`https://satori.chat/zh-CN/protocol/events.html`
- HTTP API：`https://satori.chat/en-US/protocol/api.html`
- Message 资源：`https://satori.chat/zh-CN/resources/message.html`
- 标准元素：`https://satori.chat/zh-CN/protocol/elements.html`
- User 资源：`https://satori.chat/zh-CN/resources/user.html`
- Member 资源：`https://satori.chat/zh-CN/resources/member.html`
- 被动请求：`https://satori.chat/en-US/advanced/passive.html`

