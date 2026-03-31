# Satori API 调用说明

本文整理了当前项目中实际使用到的 Satori API、调用方式和文档地址，方便在其他项目中复用相同的消息转发能力。

## 1. 接入地址

### HTTP API 基址

```text
http://127.0.0.1:5600/v1
```

### WebSocket 事件流

```text
ws://127.0.0.1:5600/v1/events
```

## 2. 鉴权与通用请求头

当前项目中，Satori HTTP API 的调用方式如下：

```http
POST /v1/{action}
Content-Type: application/json
Authorization: Bearer <SATORI_TOKEN>
Satori-Platform: <platform>
Satori-User-ID: <self_id>
```

其中：

- `SATORI_TOKEN`：Satori 鉴权 token，必填
- `Satori-Platform`：平台标识，例如 `chronocat`
- `Satori-User-ID`：机器人自身 ID

## 3. 当前项目实际使用到的 Satori API

### 3.1 `message.create`

用途：发送消息回复。

```http
POST /v1/message.create
```

示例：

```json
{
  "channel_id": "<channel_id>",
  "content": "你好，世界"
}
```

---

### 3.2 `message.get`

用途：获取单条消息详情，常用于引用消息、回复上下文补全、转发消息还原。

```http
POST /v1/message.get
```

示例：

```json
{
  "channel_id": "<channel_id>",
  "message_id": "<message_id>"
}
```

---

### 3.3 `message.list`

用途：获取频道消息列表，用于建立历史消息索引。

```http
POST /v1/message.list
```

示例：

```json
{
  "channel_id": "<channel_id>",
  "limit": 100
}
```

---

### 3.4 `reaction.create`

用途：给消息添加表态，例如“处理中”标记。

```http
POST /v1/reaction.create
```

示例：

```json
{
  "channel_id": "<channel_id>",
  "message_id": "<message_id>",
  "emoji_id": "30"
}
```

---

### 3.5 `reaction.delete`

用途：移除消息表态。

```http
POST /v1/reaction.delete
```

示例：

```json
{
  "channel_id": "<channel_id>",
  "message_id": "<message_id>",
  "emoji_id": "30"
}
```

---

### 3.6 `internal/onebot11/get_forward_msg`

用途：获取 OneBot11 合并转发消息内容。

```http
POST /v1/internal/onebot11/get_forward_msg
```

示例：

```json
{
  "message_id": "<forward_message_id>"
}
```

说明：

- 这是 `internal` 接口
- 不属于标准 Satori 公共 API
- 是否可用取决于你所使用的 Satori 后端实现

## 4. WebSocket 调用方式

当前项目通过 WebSocket 订阅 Satori 事件流，并在连接成功后主动发送 `IDENTIFY`。

连接地址：

```text
ws://127.0.0.1:5600/v1/events
```

连接后发送：

```json
{
  "op": 3,
  "body": {
    "token": "<SATORI_TOKEN>",
    "sn": 123
  }
}
```

其中：

- `token`：Satori token
- `sn`：可选，事件序号，用于断线续传

## 5. 当前项目主要处理的 WebSocket Opcode

```text
0  EVENT
1  PING
2  PONG
3  IDENTIFY
4  READY
5  META
```

在当前项目中主要使用：

- `IDENTIFY (3)`：鉴权
- `READY (4)`：登录成功
- `EVENT (0)`：接收消息事件

## 6. 当前项目里与消息转发最相关的最小 API 集合

如果你只需要做“接收消息 -> 转发消息”，最少只需要：

```text
WebSocket:
- /v1/events

HTTP:
- message.create
```

如果你还需要支持：

- 引用消息还原
- 历史消息补全
- 转发消息解析
- 消息表态

则还需要：

```text
- message.get
- message.list
- reaction.create
- reaction.delete
- internal/onebot11/get_forward_msg（如果后端支持）
```

## 7. 官方文档地址

### 协议总览

```text
https://satori.chat/zh-CN/protocol/
```

### HTTP API 总说明

```text
https://satori.chat/en-US/protocol/api.html
```

### WebSocket / Events

```text
https://satori.chat/en-US/protocol/events.html
```

### Passive Requests / 被动回复

```text
https://satori.chat/en-US/advanced/passive.html
```

### Internal API

```text
https://satori.chat/zh-CN/advanced/internal.html
```

## 8. 迁移到其他项目时的注意事项

1. `message.create` 是最核心的发送接口。
2. `Satori-Platform` 和 `Satori-User-ID` 请求头不能漏。
3. `internal/onebot11/get_forward_msg` 不是标准接口，跨项目前要确认目标 Satori 服务端是否支持。
4. WebSocket 断线重连时，建议保存并回传 `sn`，这样可以减少漏事件。
5. 如果只是做简单消息转发，优先实现：
   - `events`
   - `message.create`

这样复杂度最低，也最稳定。
