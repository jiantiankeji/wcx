# 服务器通知转发（卡片）

手机上的微信无法被公网服务器反向连接，所以由插件**主动**与你的服务器建立 WebSocket 长连接；
服务器下发通知后，插件把通知以**卡片**形式转发到指定群聊。

> 插件 ID（目录名）：`server_notify`
> 协议与模块内置的「服务器通知转发」(`WcxNotifyClient`) 完全一致，可直接对接 `jzyh_backend`。

## 一、它做什么

```
你的服务器 ──WebSocket──▶ 微信进程内的插件 ──卡片──▶ 群聊 A / 群聊 B
   │                          │
   │  notify 帧               └─ 可选：本地 Toast 提示
   └─ 心跳 pong
```

- 自动连接、断线重连（1s → 60s 指数退避）
- 30 秒心跳保活（可在配置里改）
- 目标群支持多个；本地未配置时回退服务端下发的 `convId`
- 同一条通知（按 `id`）10 分钟内只转发一次
- 卡片发送失败自动回退为文本，避免通知丢失

## 二、安装

1. `server_notify/` 放到
   `/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件/server_notify/`
   （或导入 `server_notify.zip`）
2. 总开关 + 本插件开关打开
3. 点齿轮，填「服务器地址」和「共享令牌」，再填「目标群」
4. 看插件 `log.txt` 出现 `已连接 host:port` 即成功

> ⚠️ 模块内置的「服务器通知转发」功能与本插件协议相同。
> 两者**同时开启会重复转发**，请二选一。

## 三、协议

连接地址：

```
ws(s)://<host>[:port]/ws/wcx-notify?token=<共享令牌>
```

| 方向 | 帧 |
|---|---|
| 客户端 → 服务端 | `{"type":"hello","wxId":"..."}` |
| 客户端 → 服务端 | `{"type":"ping"}`（心跳） |
| 服务端 → 客户端 | `{"type":"pong"}` |
| 服务端 → 客户端 | `{"type":"notify", ...}` |

`notify` 字段：

```json
{
  "type": "notify",
  "id": 42,
  "title": "服务器通知",
  "content": "部署完成",
  "cardTitle": "服务器通知",
  "cardDesc": "部署完成",
  "link": "https://example.com/1",
  "convId": "123@chatroom",
  "path": "pages/post/post-detail?id=42",
  "thumbUrl": "https://example.com/cover.png"
}
```

| 字段 | 说明 |
|---|---|
| `id` | 去重用，建议必填 |
| `title` / `content` | 通知原文；本地 Toast 用 |
| `cardTitle` / `cardDesc` | 群内展示文案（优先于 title/content） |
| `link` | 链接卡片跳转地址 |
| `convId` | 本地未配置目标群时的回退目标 |
| `path` | 小程序卡片页面路径（type=33 用） |
| `thumbUrl` | 卡片封面图（≤ 32KB 直接用，大图会压缩） |

## 四、配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `server_url` | 空 | `https://host`、`wss://host` 或完整 `ws://` 地址 |
| `token` | 空 | 共享令牌，作为 `?token=` 传递 |
| `groups` | 空 | 目标群，逗号分隔 `xxx@chatroom`；留空用 `convId` |
| `card_type` | `link` | `link` 链接卡片 / `text` 纯文本 / `miniprogram` 小程序卡片 |
| `card_template` | 空 | 小程序卡片模板 XML（一条真实卡片报文） |
| `page_path_template` | 空 | 小程序页面路径模板，`{id}` 替换通知 ID |
| `show_toast` | `true` | 收到通知时本地弹 Toast |
| `ping_seconds` | `30` | 心跳间隔 / 读超时 |
| `send_interval_ms` | `300` | 多群转发之间的间隔 |
| `app_id` | 空 | 链接卡片附带的 appId，可留空 |

## 五、三种卡片形式

### 1. 链接卡片（默认，推荐）

调用 `shareWebpage` 发送标准网页卡片：标题、描述、链接、封面。

- 封面会下载并压缩到 32KB 以内（微信对 `thumbData` 的限制）
- 不需要任何模板，开箱即用

### 2. 纯文本

```
【标题】
内容
详情：链接
```

最稳，永不失败。

### 3. 小程序卡片（type=33）

需要把**一条真实可打开的小程序卡片 XML** 粘贴到「小程序卡片模板」。
插件只替换 `<title>`、`<des>`、`<pagepath>`、`<weapppagethumbrawurl>`，
`username` / `appid` / `version` / `icon` 等由微信生成的字段全部沿用模板。

> 与内置功能不同，脚本插件无法把封面字节单独交给 appmsg 发送管线，
> 因此小程序卡片的封面可能为空。需要完整封面效果时请使用内置功能或链接卡片。

## 六、常见问题

| 现象 | 排查 |
|---|---|
| 一直「连接失败」 | 地址/令牌是否正确；服务器是否启用 `/ws/wcx-notify`；看 `log.txt` 的具体异常 |
| 连上但收不到通知 | 服务端是否发 `{"type":"notify",...}`；看日志有没有「收到通知 #id」 |
| 收到通知但群里没消息 | 目标群是否填对（`xxx@chatroom`）；卡片失败会回退文本，看日志 |
| 群里发了两条 | 内置功能与本插件同时开启了 |
| 重复通知 | 服务端每次重连都重发历史；请带稳定 `id`，插件按 id 去重 |
| wss 证书错误 | 服务器证书需被系统信任；自签证书请改用内置功能或换正式证书 |

## 七、注意

- 令牌明文保存在 `config.prop`，注意插件目录权限
- 通知内容会进入群聊，请确保服务端下发的是可公开的文案
- 插件只保持**一条**长连接；修改服务器/令牌后会自动断开重连
