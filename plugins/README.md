# WCX 脚本插件合集

四个可直接安装的 WCX「脚本插件」，覆盖通知转发、AI 回复、朋友圈导出、进群欢迎。

| 插件 | 目录 ID | 功能 | 触发 |
|---|---|---|---|
| 服务器通知转发 | `server_notify` | 主动连服务器 WebSocket，收到通知后以卡片转发到群聊 | 服务端下发 |
| 阿里云智能体回复 | `aliyun_agent_reply` | 百炼智能体 / OpenAI 兼容模型自动回复 | 私聊 / 群聊 @ / 全部 |
| 导出朋友圈 | `moments_export` | 朋友圈 → JSON + Markdown + 图片视频 | 「+」菜单 / 设置 |
| 新成员进群欢迎 | `group_welcome` | 进群自动发带真实 @ 的欢迎语 | 成员变动事件 |

## 一、安装（三选一）

### 方式 A：拷贝目录

把需要的插件目录整体放到：

```
/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件/
```

最终结构：

```
脚本插件/
├── server_notify/
│   ├── main.java
│   ├── info.prop
│   └── README.md
├── aliyun_agent_reply/
├── moments_export/
└── group_welcome/
```

### 方式 B：导入 ZIP（推荐）

微信 → 我 → 设置 → WCX 设置 → 脚本 → **本地插件管理** → 导入：

- 单个：`dist/<插件>.zip`
- 全部：`dist/wcx-plugins-bundle.zip`

导入后插件默认是**关闭**的，需要手动打开。

### 方式 C：在线市场

目前这些插件未上传市场；如需上传，在「在线插件」页选择本地插件上传即可。

## 二、启用

```
微信 → 我 → 设置 → WCX 设置 → 脚本
  1. 打开「插件总开关」
  2. 在「本地插件」列表里打开对应插件
  3. 点插件行齿轮进入该插件的设置
```

> 总开关会检查微信联系人数据库是否就绪；若提示未就绪，等待几秒或到「脚本功能诊断」点一键修复。

## 三、各插件文档

| 插件 | 文档 |
|---|---|
| 服务器通知转发 | [server_notify/README.md](server_notify/README.md) |
| 阿里云智能体回复 | [aliyun_agent_reply/README.md](aliyun_agent_reply/README.md) |
| 导出朋友圈 | [moments_export/README.md](moments_export/README.md) |
| 新成员进群欢迎 | [group_welcome/README.md](group_welcome/README.md) |

## 四、开发与自测

这些插件是标准的 WCX 脚本插件：`info.prop` + `main.java`（BeanShell）。

本仓库附带一个本地校验工具（不随插件分发）：

- `build/plugin-check/ParseCheck.java`：只做 BeanShell 语法解析
- `build/plugin-check/Harness.java`：用桩环境真正执行脚本，覆盖消息回调、菜单回调、设置读写、JSON 构造、WebSocket 握手/心跳/重连与卡片转发、AI 接口调用、朋友圈文件导出

手动运行（需要 JDK 21）：

```powershell
$jar = "libs\common\bsh\build\intermediates\compile_library_classes_jar\release\bundleLibCompileToJarRelease\classes.jar"
javac -encoding UTF-8 -cp $jar -d build\plugin-check\out `
  build\plugin-check\org\json\JSONException.java `
  build\plugin-check\org\json\JSONArray.java `
  build\plugin-check\org\json\JSONObject.java `
  build\plugin-check\Harness.java

$android = "C:\Users\Administrator\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar"

java --add-opens java.base/java.lang=ALL-UNNAMED `
     --add-opens java.base/java.util=ALL-UNNAMED `
     --add-opens java.base/java.util.function=ALL-UNNAMED `
     --add-opens java.base/java.util.concurrent=ALL-UNNAMED `
     --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED `
     --add-opens java.base/java.util.random=ALL-UNNAMED `
     --add-opens java.base/java.io=ALL-UNNAMED `
     --add-opens java.base/java.net=ALL-UNNAMED `
     --add-opens java.base/sun.net.www.protocol.http=ALL-UNNAMED `
     --add-opens java.base/java.text=ALL-UNNAMED `
     --add-opens java.base/java.nio=ALL-UNNAMED `
     --add-opens java.base/java.nio.charset=ALL-UNNAMED `
     --add-opens java.base/java.security=ALL-UNNAMED `
     --add-exports java.base/sun.security.util=ALL-UNNAMED `
     --add-exports java.base/sun.security.provider=ALL-UNNAMED `
     -cp "$jar;build\plugin-check\out;$android" Harness plugins
```

> `--add-opens` / `--add-exports` 只是让 BeanShell 3 在 JDK 21 上跑起来，Android 上不需要。

## 五、安全提示

脚本插件以微信进程身份运行，**没有沙箱**：能读数据库、发消息、Hook 任意方法、访问网络。
请只安装你信任的插件；这四个插件的全部源码都在本目录，可直接审计。

- 服务器地址 / API Key / Token 以明文保存在各插件的 `config.prop`
- 转发与 AI 回复会把聊天内容发送到第三方服务器，请自行评估隐私与合规
