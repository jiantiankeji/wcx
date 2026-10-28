# WCX

WCX 是一个功能丰富的微信 Xposed 模块, 提供大量微信增强功能, 涵盖聊天体验、界面美化、隐私保护、自动化等多个方面。当前代码中共有 **200+ 个功能开关，分为 20 余个分类**。

[![CI 状态](https://github.com/Johnny520/wcx/actions/workflows/ci.yml/badge.svg)](https://github.com/Johnny520/wcx/actions/workflows/ci.yml)

## 特色功能

### 聊天

- **防撤回**：两种展示样式二选一 —— 新版「原消息半透明 + 文本 / 图标徽标」，或旧版「插入一条系统提示」（提示内容与时间格式可自定义）；支持把历史遗留的旧版系统提示一键迁移为新样式
- **AI 自动回复 / 关键词自动回复 / 长按智能回复**：可指定触发条件与生效群聊
- **对话分组 / 对话归拢 / 超级对话置顶**：主页 Tab 分类、多个对话收进文件夹
- **定时发送 / 群发消息 / 转发消息 / 批量撤回 / 消息复读**
- **消息过滤屏蔽、群内屏蔽指定成员、群成员行为监控**
- **Markdown 消息渲染、合并消息显示、消息进入动画、悬浮标题栏 / 输入框、沉浸式状态栏**
- **自动查看原图、自动缓存图片与文件、图片 / 文件 / 语音 / 贴纸保存本地、半屏相册选择器**

### 界面与主题

- 主题商城 / 四 Tab 主题背景 / 莫奈引擎（动态取色）
- 微信主页侧边栏（内置面板或负一屏）
- 美化对话列表、首页底部导航栏、主屏幕 FAB、自定义消息气泡、对话框背景模糊

### 联系人与朋友圈

- 隐藏联系人（会话 / 通讯录 / 搜索 / 朋友圈 / 通话等隐藏面全覆盖）
- 检测单向删除好友、自动同意好友申请、添加自动备注
- 朋友圈防撤回、自动点赞、一键转发、拦截广告、伪集赞

### 红包、支付与批量操作

- 自动抢红包、抢红包金额统计、捡漏历史红包、指纹支付、自动接收转账
- 批量删除好友 / 聊天记录、批量打标签、批量免打扰、批量隐藏对话、群发消息、导出聊天记录

### 系统、安全与自动化

- 反 Xposed 检测、Native 反检测、环境伪装、隐藏模块应用
- 虚拟定位、修改运动步数、强制平板模式、DPI 修改、灰度测试管理器
- **脚本插件中心**：独立的「脚本」Tab，本地插件管理与在线插件市场，修改插件后实时加载；附带功能诊断、常见问题、一键自检与一键修复
- **API 服务器**：启动 MCP 与 REST API 服务器，可让外部程序与 AI 访问微信能力
- **在线特征包**：Hook 点特征支持在线更新，微信更新后无需重发 APK

## 导航

- [🚀 快速开始](getting-started.md)
- [📥 安装指南](installation.md)
- [⚙️ 配置指南](configuration.md)
- [🔧 模块设置说明](module-settings.md)
- [📚 功能目录](SUMMARY.md)
- [❓ 常见问题](faq.md)
- [🛠 开发指南](development.md)

## 功能分类索引

功能明细和开关说明见 [功能目录](SUMMARY.md)；下面是各分类的入口：

| 分类 | 说明 | 文档 |
| --- | --- | --- |
| 批量操作 | 批量删除/打标签/免打扰、群发、导出聊天记录 | [features/batch](features/batch/README.md) |
| 界面美化 | 主题、气泡、侧边栏、导航栏、模糊与动画 | [features/beautify](features/beautify/README.md) |
| 聊天 | 防撤回、自动回复、定时发送、消息过滤、合并消息等 | [features/chat](features/chat/README.md) |
| 聊天输入栏菜单 | 输入栏「+」面板与菜单扩展 | [features/chat_input_bar_menu](features/chat_input_bar_menu/README.md) |
| 联系人与群组 | 隐藏联系人、单向删除检测、群成员、退群监控 | [features/contacts](features/contacts/README.md) |
| 调试 | 调试选项与诊断入口 | [features/debug](features/debug/README.md) |
| 彩蛋 | 隐藏彩蛋功能 | [features/easter_egg](features/easter_egg/README.md) |
| 娱乐 | 娱乐类小功能 | [features/entertain](features/entertain/README.md) |
| 首页右上角菜单 | 首页「+」菜单扩展 | [features/home_screen_menu](features/home_screen_menu/README.md) |
| 小程序 | 小程序相关增强 | [features/miniapps](features/miniapps/README.md) |
| 朋友圈 | 防撤回、自动点赞、导出、广告拦截 | [features/moments](features/moments/README.md) |
| 通知 | 通知与提醒增强 | [features/notifications](features/notifications/README.md) |
| 公众号 | 公众号文章与菜单增强 | [features/official_accounts](features/official_accounts/README.md) |
| 红包与支付 | 自动抢红包、指纹支付、转账接收 | [features/payment](features/payment/README.md) |
| 个人资料 | 「我」页面与资料卡 | [features/profile](features/profile/README.md) |
| Java 脚本 | Java 脚本插件 API 与示例 | [features/scripting_java](features/scripting_java/README.md) |
| JS 脚本 | JavaScript 脚本插件 API 与示例 | [features/scripting_js](features/scripting_js/README.md) |
| 视频号 | 视频号相关增强 | [features/shortvideos](features/shortvideos/README.md) |
| 系统与隐私 | 环境伪装、反检测、定位、缓存、权限 | [features/system](features/system/README.md) |
| 音视频通话 | 通话界面与虚拟视频 | [features/voip](features/voip/README.md) |

## 脚本插件与工具

| 文档 | 说明 |
| --- | --- |
| [脚本插件合集](../plugins/README.md) | server_notify / aliyun_agent_reply / moments_export / group_welcome 的安装、配置与协议 |
| [去混淆工具](../deobf/README.md) | unidbg 解密与反混淆流水线（独立 GPL-3.0 子项目） |

## 与上游 [WeKit](https://github.com/Ujhhgtg/WeKit) 的关系

本项目基于 WeKit 二次开发，并在此基础上做了大量调整：

- 添加 WAuxiliary 与 NewMiko 目前公开源代码中的部分功能
- 移除全部校验，减少模块体积，避免不必要的性能开销
- 移植 UI 至 Jetpack Compose
- 添加、修复、增强 WAuxiliary 部分闭源功能
- 移植其他模块的一些功能
- 反射层与原生库按本项目需要重构
- 支持 arm64-v8a / armeabi-v7a 两种 ABI
- 新增脚本插件中心（本地插件 + 在线市场）、模块自检与脚本诊断等稳定性能力
- 修复问题、新增功能（见仓库提交记录）

## 联系

[GitHub 仓库](https://github.com/Johnny520/wcx)

[个人官网](https://johnny520.github.io/Johnny/)

[Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

[❤️ 爱发电赞助](https://afdian.com/a/Johnny520)

## 致谢

[WeKit 上游](https://github.com/Ujhhgtg/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/dartcv/NewMiko/blob/archives/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[WADN](https://github.com/Johnny520/wauxv_deobf_new) [WAD](https://github.com/Johnny520/wauxv_deobf)
