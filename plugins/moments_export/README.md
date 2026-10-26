# 导出朋友圈

把本地已缓存的朋友圈导出成 **JSON + Markdown + 原始图片/视频**，方便备份、迁移或做二次分析。

> 插件 ID（目录名）：`moments_export`

## 一、安装

1. `moments_export/` 放到
   `/storage/emulated/0/Android/media/com.tencent.mm/WCX/脚本插件/moments_export/`
   （或导入 `moments_export.zip`）
2. 总开关 + 本插件开关打开
3. 两种触发方式：
   - 聊天输入框「+」→ **导出朋友圈**
   - 插件行齿轮 → 设置 → **立即导出**

## 二、导出结果

```
/storage/emulated/0/Download/WCX朋友圈/<yyyyMMdd-HHmmss>/
├── posts.json      # 结构化数据，含每条动态与媒体 URL/本地文件名
├── index.md        # 可直接阅读的 Markdown
└── media/
    ├── <snsId>_0.jpg
    ├── <snsId>_1.jpg
    └── <snsId>_0.mp4        # 视频
```

`posts.json` 结构：

```json
{
  "exportTime": "2026-10-19 13:50:00",
  "self": "wxid_me",
  "count": 2,
  "onlySelf": false,
  "mediaSaved": 3,
  "mediaFailed": 0,
  "posts": [
    {
      "snsId": "1350000000000000000",
      "userName": "wxid_friend",
      "displayName": "张三",
      "time": 1697600000,
      "timeText": "2023-10-18 11:33:20",
      "type": "image",
      "self": false,
      "text": "今天天气不错",
      "media": [
        {"type": 1, "url": "https://...", "thumbUrl": "https://...", "file": "media/xxx_0.jpg"}
      ]
    }
  ]
}
```

## 三、配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `limit` | `50` | 导出最近多少条（1–500） |
| `only_self` | `true` | 只导出自己发的；关掉则导出所有可见缓存 |
| `download_media` | `true` | 是否下载图片/视频到 `media/`；关掉只记录 URL |
| `output_dir` | 空 | 留空用 `/storage/emulated/0/Download/WCX朋友圈` |
| `max_media_mb` | `200` | 单个媒体大小上限（MB），超过则跳过，`0` 不限制 |

## 四、使用要点

- **数据来源是微信本地朋友圈缓存**：朋友圈翻得越多，能导出的越多；刚登录/很久没刷时可能只有很少几条
- 导出在后台线程执行，可继续用微信；插件被关闭/重载会取消任务
- 视频、实况照片（Live Photo）会额外下载视频文件（`*_live.mp4`）
- 单条失败不会中断整体，失败原因写入插件 `log.txt`
- 媒体下载失败通常是 URL 过期或网络问题，可稍后重试

## 五、注意

- 导出内容包含他人隐私，请自行妥善保管
- 媒体 URL 可能带时效，`posts.json` 里的远程地址不保证长期可访问；需要长期保存请保留 `media/`
- 大量导出会消耗流量与存储，建议先设 `limit=20` 试跑
