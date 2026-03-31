# Satori API 调用说明

本文整理了当前项目实际需要关注的 Satori 协议内容，重点覆盖：

- WebSocket 事件流接入
- `PING` / `PONG` 心跳
- `IDENTIFY` / `READY` 鉴权与登录上下文
- 会话恢复
- HTTP `message.create` 发消息
- `message-created` 事件接收
- 被动请求 `referrer`

本文面向“消息转发”类项目，不追求覆盖全部 Satori 能力，只保留当前最相关的部分。

## 1. 接入地址

### HTTP API 基础地址

```text
http://127.0.0.1:5600/v1
```

### WebSocket 事件流

```text
ws://127.0.0.1:5600/v1/events
```

说明：

- WebSocket 用于接收事件和维护会话状态。
- HTTP API 用于发送消息和调用其他功能。
- 这两部分是 Satori 标准协议中的两套能力，不能简单互相替代。

## 2. HTTP API 基本规则

Satori 的 HTTP API 是 RPC 风格，URL 形如：

```text
/{version}/{resource}.{method}
```

当前标准版本为：

```text
v1
```

大部分请求都使用：

```http
POST /v1/{action}
Content-Type: application/json
Authorization: Bearer <SATORI_TOKEN>
Satori-Platform: <platform>
Satori-User-ID: <self_id>
```

其中：

- `Authorization`：可选，取决于 SDK 是否启用了鉴权。
- `Satori-Platform`：必需，请求对应登录实例的平台名，例如 `chronocat`。
- `Satori-User-ID`：必需，请求对应登录实例的机器人/应用账号 ID。

注意：

- `Satori-Platform` 和 `Satori-User-ID` 不能凭空写死，通常应在 WebSocket 收到 `READY.logins` 或后续事件中的 `login` 后再使用。
- 如果 HTTP 请求缺少这两个头，即使 token 正确，也可能被 SDK 拒绝。

## 3. 当前项目最核心的 HTTP API

### 3.1 `message.create`

用途：向指定频道发送消息。

```http
POST /v1/message.create
```

最小请求体示例：

```json
{
  "channel_id": "<channel_id>",
  "content": "你好，世界"
}
```

补充说明：

- 对于 QQ 一类平台，实际发送目标通常应填写群聊对应的 `channel_id`。
- 在部分平台中，`guild` 和群聊频道概念重合，但发消息时仍应优先使用 `channel_id`。
- `content` 使用 Satori 的消息元素编码；纯文本场景可直接传文本。

### 3.2 其他当前文档中提到的接口

这些接口不是当前模组的必需项，但在其他项目中可能会用到：

- `message.get`
- `message.list`
- `reaction.create`
- `reaction.delete`
- `internal/onebot11/get_forward_msg`

其中：

- `internal/onebot11/get_forward_msg` 是内部接口，不属于标准公共 API。
- 是否可用，取决于具体 Satori 后端或适配器实现。

## 4. WebSocket 事件流

当前项目通过 WebSocket 订阅 Satori 事件流：

```text
ws://127.0.0.1:5600/v1/events
```

WebSocket 是一个持续连接。应用连接后，需要主动发送鉴权和心跳，随后持续接收事件。

### 4.1 Opcode 定义

```text
0  EVENT      接收：事件
1  PING       发送：心跳
2  PONG       接收：心跳回复
3  IDENTIFY   发送：鉴权 / 会话恢复
4  READY      接收：鉴权成功 / 当前登录信息
5  META       接收：元信息更新（实验性）
```

### 4.2 连接流程

标准连接流程如下：

1. 连接建立后，应用必须在 10 秒内发送 `IDENTIFY`。
2. SDK 回复 `READY`，其中包含当前登录信息 `logins`。
3. 连接建立后，应用应当每隔 10 秒发送一次 `PING`。
4. SDK 回复 `PONG`。
5. 应用持续接收 `EVENT`。
6. 若连接中断，可在下次 `IDENTIFY` 中带上上一次收到的最后事件序号 `sn`，尝试恢复会话。

## 5. WebSocket 信令格式

所有 WebSocket 信令都遵循：

```json
{
  "op": 3,
  "body": {}
}
```

字段说明：

- `op`：Opcode
- `body`：具体信令内容，可选

## 6. `IDENTIFY`

连接建立后，应用发送：

```json
{
  "op": 3,
  "body": {
    "token": "<SATORI_TOKEN>",
    "sn": 123
  }
}
```

字段说明：

- `token`：可选，SDK 开启鉴权时需要提供。
- `sn`：可选，用于会话恢复。值应为上一次连接中最后一个收到的 `EVENT.body.sn`。

注意：

- `IDENTIFY` 要在连接后的 10 秒内发送。
- 如果没有开启鉴权，可以不传 `token`。
- `sn` 不是登录账号 ID，也不是消息 ID，它只是事件流序号。

## 7. `READY`

`READY` 表示鉴权成功，且当前连接已进入可接收事件状态。

示意结构：

```json
{
  "op": 4,
  "body": {
    "logins": [
      {
        "platform": "chronocat",
        "user": {
          "id": "123456"
        },
        "status": 1
      }
    ],
    "proxy_urls": []
  }
}
```

`READY.body` 关键字段：

- `logins`：当前所有登录实例
- `proxy_urls`：代理路由列表

对业务最重要的是：

- `logins[i].platform`
- `logins[i].user.id`

这两个值通常应缓存下来，后续调用 HTTP API 时作为：

- `Satori-Platform`
- `Satori-User-ID`

## 8. `PING` / `PONG`

这是当前旧文档遗漏最明显的一部分。

### 8.1 规则

根据官方事件协议：

- 应用在 WebSocket 连接建立后，应每隔 10 秒发送一次 `PING`
- SDK 收到后，会回复 `PONG`

### 8.2 最小示例

发送：

```json
{
  "op": 1
}
```

接收：

```json
{
  "op": 2
}
```

也可以带 `body`，但在当前简单转发场景下，最小空体即可。

### 8.3 实践建议

- 使用定时任务固定每 10 秒发送一次 `PING`
- 收到 `PONG` 仅作为连接存活确认即可
- 如果持续未收到 `PONG` 或连接关闭，应主动重连
- 重连后重新发送 `IDENTIFY`

## 9. `META`

`META` 是实验性信令，用于更新 SDK 的元信息。

示意结构：

```json
{
  "op": 5,
  "body": {
    "proxy_urls": []
  }
}
```

说明：

- `META` 不表示登录状态变化
- `META` 不包含 `logins`
- 如果项目不依赖代理路由，可以先忽略

## 10. `EVENT`

`EVENT` 是业务事件承载体，格式上通常为：

```json
{
  "op": 0,
  "body": {
    "sn": 456,
    "type": "message-created",
    "timestamp": 1710000000000,
    "login": {
      "platform": "chronocat",
      "user": {
        "id": "123456"
      }
    },
    "channel": {
      "id": "987654321"
    },
    "guild": {
      "id": "987654321"
    },
    "user": {
      "id": "111222333",
      "name": "Alice"
    },
    "message": {
      "id": "abc",
      "content": "hello"
    }
  }
}
```

### 10.1 关键字段

- `sn`：事件流序号，用于断线恢复
- `type`：事件类型
- `login`：当前事件所属登录实例
- `channel`：事件所在频道
- `guild`：事件所在群组
- `user`：事件对应用户
- `message`：消息对象

### 10.2 当前消息转发最相关的事件类型

```text
message-created
```

该事件用于表示收到了一条新消息。

当前项目对它的典型处理方式是：

1. 过滤目标群/频道
2. 过滤机器人自身消息，避免回声
3. 读取 `user.id` / `user.name` / `message.content`
4. 转发到 Minecraft 公屏

## 11. 会话恢复

当连接短暂断开时，可通过 `IDENTIFY.body.sn` 恢复会话。

恢复方式：

1. 每次收到 `EVENT` 时，保存其中的 `body.sn`
2. 重连后重新发送 `IDENTIFY`
3. 将保存的 `sn` 放入 `IDENTIFY.body.sn`

示例：

```json
{
  "op": 3,
  "body": {
    "token": "<SATORI_TOKEN>",
    "sn": 456
  }
}
```

效果：

- SDK 会补推连接中断期间发生的事件
- 登录事件不会在会话恢复阶段补推，因为最新登录状态已包含在 `READY` 中

## 12. 登录对象 `Login`

`READY.logins` 和部分事件中的 `login` 都很重要。

关键字段：

- `platform`
- `user.id`
- `status`
- `features`

注意：

- 非登录事件中的 `login` 通常只包含业务真正需要的少数字段
- 对当前项目来说，最关键的是 `platform` 和 `user.id`
- `login.sn` 只是 Login 对象自己的标识，不等于事件 `sn`

## 13. 被动请求 `referrer`

这是当前旧文档中遗漏的另一个重要点。

Satori 的主动发消息和被动回复，在协议层面都统一使用：

```text
/v1/message.create
```

但某些平台要求“回复消息”时必须附带原始事件上下文，此时就需要使用：

```json
{
  "channel_id": "xxxx",
  "content": "reply",
  "referrer": { ... }
}
```

说明：

- `referrer` 来源于收到的事件体
- 它的内部结构由适配器定义，不是协议统一字段结构
- 如果只是普通主动发消息，可以不传
- 如果是做“回复某条消息”或平台要求上下文绑定的被动操作，应原样透传

对于当前 Minecraft <-> 群聊桥接场景：

- 从 MC 转发到群，一般属于主动发送，通常不需要 `referrer`
- 如果将来要实现“引用 QQ 消息后在游戏内触发回复”，则应补上这一字段传递

## 14. 当前项目真正需要的最小协议集合

如果目标只是“接收群消息并转发到 Minecraft，同时把游戏内聊天发到群里”，最小只需要：

### WebSocket

- `/v1/events`
- `IDENTIFY`
- `PING`
- `PONG`
- `READY`
- `EVENT`

### HTTP

- `message.create`

### 事件

- `message-created`

## 15. 实现建议

### 15.1 建议缓存的数据

- `token`
- 最后一个 `EVENT.sn`
- `login.platform`
- `login.user.id`
- 目标 `channel_id`

### 15.2 建议的连接策略

- 连接建立后立即发送 `IDENTIFY`
- 建立固定 10 秒心跳
- 收到 `READY` 后缓存登录上下文
- 每次收到 `EVENT` 更新最新 `sn`
- 连接关闭或异常后重连
- 重连时带上最近的 `sn`

### 15.3 发送消息前的前置条件

在调用 `message.create` 前，通常应确保：

- WebSocket 已成功连上
- 已收到 `READY`
- 已获取有效的 `platform`
- 已获取有效的 `self_id`

否则即使 token 正确，也可能无法构造合法的 HTTP 请求头。

## 16. 常见坑

1. 只配了 token，但没有从 `READY` 中拿 `platform` 和 `self_id` 就直接调 HTTP API。
2. 误把 `guild_id` 当成发消息时唯一目标字段，而实际应传 `channel_id`。
3. WebSocket 连上后没有在 10 秒内发送 `IDENTIFY`。
4. 忽略 `PING` / `PONG`，导致长连接被动断开后迟迟不重连。
5. 把 `login.sn` 和 `event.sn` 混淆。
6. 会话恢复时没有保存最后一个 `EVENT.sn`。
7. 误以为“既然已经用了 WebSocket，就不需要 HTTP API 发消息”。标准 Satori 协议不是这样设计的。
8. 把 `internal/*` 接口当成标准公共接口来依赖。

## 17. 官方文档地址

### 协议总览

```text
https://satori.chat/zh-CN/protocol/
```

### HTTP API

```text
https://satori.chat/en-US/protocol/api.html
```

### WebSocket / Events

```text
https://satori.chat/zh-CN/protocol/events.html
```

### Login 资源

```text
https://satori.chat/zh-CN/resources/login.html
```

### Channel 资源

```text
https://satori.chat/zh-CN/resources/channel.html
```

### Passive Requests

```text
https://satori.chat/en-US/advanced/passive.html
```

### Meta API

```text
https://satori.chat/zh-CN/advanced/meta.html
```

### Internal API

```text
https://satori.chat/zh-CN/advanced/internal.html
```

## 18. 给当前项目的结论

当前这个消息转发模组，标准且稳定的实现方式应当是：

- 用 WebSocket `/v1/events` 接收群消息与登录上下文
- 在连接后 10 秒内发送 `IDENTIFY`
- 每 10 秒发送 `PING`，并接收 `PONG`
- 保存 `EVENT.sn` 以支持断线恢复
- 用 `READY.logins` 或事件中的 `login` 提取 `platform` 与 `self_id`
- 用 HTTP `message.create` 将 Minecraft 消息发送回目标群

这样实现复杂度最低，也最符合 Satori 标准协议。
