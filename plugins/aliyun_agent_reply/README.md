# 阿里云智能体回复

用阿里云百炼（DashScope）的智能体应用，或任意 OpenAI 兼容模型，自动回复微信消息。

> 插件 ID（目录名）：`aliyun_agent_reply`

## 一、安装

1. `aliyun_agent_reply/` 放到
   `/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件/aliyun_agent_reply/`
   （或导入 `aliyun_agent_reply.zip`）
2. 总开关 + 本插件开关打开
3. 点齿轮，依次填：**App ID、API Key、触发方式**

## 二、两种接入模式

### app（推荐）：百炼智能体应用

- 在[阿里云百炼控制台](https://bailian.console.aliyun.com/)创建「智能体应用」
- 拿到 **应用 ID** 与 **API Key**（`sk-...`）
- 插件请求：`POST https://dashscope.aliyuncs.com/api/v1/apps/{appId}/completion`

```json
{
  "input": {"prompt": "你好", "session_id": "wcx1a2b3c4d"},
  "parameters": {"enable_thinking": false, "has_thoughts": false}
}
```

- 应用自带的人设/知识库/插件都在百炼侧配置，插件只负责转发
- 默认开启 `session_id`，同一会话在百炼侧共享上下文
- 工作空间不是默认值时，会在设置里用到 `X-DashScope-WorkSpace`

### openai：OpenAI 兼容模型

- 默认地址：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- 默认模型：`qwen-plus`，可改成 `qwen-max`、`qwen-turbo` 等
- 插件在本地维护每个会话最近 6 轮对话（12 条消息）
- 人设（persona）作为 `system` 消息发送

> 如果你用的是其他 OpenAI 兼容服务（DeepSeek、Kimi、自建网关），把「接入点/模型」改成对应值即可。

## 三、配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `api_mode` | `app` | `app` / `openai` |
| `app_id` | 空 | 百炼应用 ID（app 模式必填） |
| `api_key` | 空 | DashScope API Key（两种模式都必填） |
| `endpoint` | `https://dashscope.aliyuncs.com` | 接入点 |
| `workspace` | 空 | 百炼工作空间，`default` 可留空 |
| `model` | `qwen-plus` | OpenAI 兼容模式的模型名 |
| `openai_url` | 兼容模式默认地址 | 可改成其他兼容服务 |
| `trigger` | `private` | `private` / `at` / `all` / `off` |
| `whitelist` | 空 | 只回复这些会话（wxid / `xxx@chatroom`） |
| `blacklist` | 空 | 不回复这些会话，优先级高于白名单 |
| `prefix` | 空 | 只处理以该前缀开头的消息，并把前缀去掉再问模型 |
| `persona` | 空 | 人设 / 附加提示词 |
| `cooldown_seconds` | `3` | 同一会话最小回复间隔 |
| `max_reply_len` | `500` | 回复最大字数，超出截断 |
| `reply_delay_ms` | `800` | 发送前延迟，模拟“正在输入” |
| `keep_session` | `true` | app 模式是否携带 `session_id` |
| `max_tokens` | `800` | OpenAI 兼容模式的 `max_tokens` |
| `timeout_seconds` | `60` | 请求超时 |

## 四、行为说明

- **只处理文本消息**，忽略自己发的消息
- 群聊 `at` 模式依赖微信的 `atuserlist` 判断“是否 @ 我”
- 群聊回复会自动带 `[AtWx=发送人]`，即真正 @ 对方
- 所有请求在独立后台线程**串行**执行；待回复队列上限 64，超了丢弃并写日志
- 同一会话冷却期内不会重复调用模型（防止对方连发多条时刷爆额度）
- 失败只写 `log.txt`，不会弹窗打扰（可用设置里的「测试一次智能体调用」定位问题）

## 五、常见问题

| 现象 | 排查 |
|---|---|
| 不回复 | 检查触发方式、白名单、冷却；看 `log.txt` 有无“回复失败” |
| `HTTP 401` | API Key 错误或过期 |
| `HTTP 403` | App ID / 工作空间无权限 |
| `HTTP 404` | App ID 或接入点不对（注意不要带 `/api/v1`） |
| `HTTP 429` | 触发限流：调大 `cooldown_seconds`，或换更高配额 |
| 回复很慢 | 百炼应用本身耗时；可把 `timeout_seconds` 调大，或换更快模型 |
| 群聊不 @ | 确认 `trigger=at`，且消息确实 @ 了你（不是纯文本 @） |

## 六、注意

- 聊天内容会发送到阿里云，请自行评估隐私与合规
- API Key 明文保存在 `config.prop`，注意插件目录权限
- 建议先在私聊小号测试，确认人设和触发范围后再放开群聊
