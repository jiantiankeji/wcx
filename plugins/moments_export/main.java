/*
 * WCX 脚本插件 · 导出朋友圈
 * ---------------------------------------------------------------------------
 * 把本地已缓存的朋友圈导出为「JSON + Markdown + 原始媒体文件」：
 *
 *   下载/WCX朋友圈/<时间戳>/
 *     ├── posts.json    结构化数据（含媒体地址与本地文件路径）
 *     ├── index.md      可直接阅读的 Markdown
 *     └── media/        图片 / 视频 / 实况照片视频
 *
 * 依赖微信本地朋友圈缓存：朋友圈翻得越多，能导出的越多。
 * 导出在独立后台线程执行，不阻塞微信；再次点击可取消正在进行的任务。
 */

Object gRunning;
Object gCancelled;
Object gMenu;
Object gWorker;
Object gActivity;

void onLoad() {
    gRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    gCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    gMenu = registerPlusMenu("导出朋友圈", new java.util.function.Consumer() {
        public void accept(Object activity) {
            startExport();
        }
    });
    log("已加载：点聊天「+」→ 导出朋友圈，或在插件设置里操作");
}

void onUnload() {
    Object cancel = gCancelled;
    Object worker = gWorker;
    if (cancel != null) cancel.set(true);
    if (worker != null) {
        try {
            ((Thread) worker).interrupt();
        } catch (Exception ignored) {
        }
        try {
            ((Thread) worker).join(2000L);
        } catch (Exception ignored) {
        }
    }
    try {
        if (gMenu != null) removeMenu(gMenu);
    } catch (Exception ignored) {
    }
    log("已卸载");
}

// ── 入口 ────────────────────────────────────────────────────────────────────
void startExport() {
    if (!gRunning.compareAndSet(false, true)) {
        toast("已有导出任务在进行");
        return;
    }
    gCancelled.set(false);
    toast("开始导出朋友圈…");
    Thread worker = new Thread(new Runnable() {
        public void run() {
            runExport();
        }
    }, "wcx-moments-export");
    worker.setDaemon(true);
    gWorker = worker;
    worker.start();
}

// ── 导出主流程 ──────────────────────────────────────────────────────────────
void runExport() {
    try {
        int limit = getInt("limit", 50);
        if (limit < 1) limit = 1;
        if (limit > 500) limit = 500;
        boolean onlySelf = getBoolean("only_self", true);
        boolean withMedia = getBoolean("download_media", true);

        String selfWxid = safe(getLoginWxid());
        java.util.List posts;
        if (onlySelf && selfWxid.length() > 0) {
            posts = getSnsPostList(selfWxid, limit);
        } else {
            posts = getSnsPostList(limit);
        }
        if (posts == null) posts = new java.util.ArrayList();
        if (posts.size() == 0) {
            toast("没有可导出的朋友圈（先多翻几屏朋友圈再试）");
            return;
        }

        String root = exportRoot();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(new java.util.Date());
        String dir = root + "/" + stamp;
        java.io.File dirFile = new java.io.File(dir);
        if (!dirFile.isDirectory() && !dirFile.mkdirs()) {
            throw new RuntimeException("无法创建导出目录: " + dir);
        }
        java.io.File mediaDir = new java.io.File(dir + "/media");
        mediaDir.mkdirs();

        org.json.JSONArray postsJson = new org.json.JSONArray();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 朋友圈导出\n\n");
        markdown.append("- 导出时间：").append(nowText()).append("\n");
        markdown.append("- 账号：").append(safe(getLoginWxid())).append("\n");
        markdown.append("- 条目数：").append(posts.size()).append("\n");
        markdown.append("- 范围：").append(onlySelf ? "仅自己" : "全部可见缓存").append("\n\n");

        int mediaSaved = 0;
        int mediaFailed = 0;

        for (int i = 0; i < posts.size(); i++) {
            if (gCancelled.get()) {
                log("导出已取消，已处理 " + i + " 条");
                break;
            }
            Object post = posts.get(i);
            try {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("snsId", safe(post.getSnsId()));
                item.put("userName", safe(post.getUserName()));
                item.put("displayName", safe(post.getDisplayName()));
                item.put("time", post.getCreateTimeSeconds());
                item.put("timeText", timeText(post.getCreateTimeSeconds()));
                item.put("type", safe(post.getType()));
                item.put("self", post.isSelf());
                item.put("text", safe(post.getText()));

                org.json.JSONArray mediaJson = new org.json.JSONArray();
                java.util.List mediaList = post.getMediaList();
                if (mediaList != null) {
                    for (int j = 0; j < mediaList.size(); j++) {
                        if (gCancelled.get()) break;
                        Object media = mediaList.get(j);
                        org.json.JSONObject mediaItem = new org.json.JSONObject();
                        String url = safe(media.getUrl());
                        int mediaType = media.getType();
                        String ext = mediaType == 15 ? ".mp4" : ".jpg";
                        String fileName = "";
                        mediaItem.put("type", mediaType);
                        mediaItem.put("url", url);
                        mediaItem.put("thumbUrl", safe(media.getThumbUrl()));
                        if (withMedia && url.length() > 0) {
                            fileName = "media/" + safe(post.getSnsId()) + "_" + j + ext;
                            if (downloadTo(url, dir + "/" + fileName)) {
                                mediaSaved++;
                                mediaItem.put("file", fileName);
                            } else {
                                mediaFailed++;
                                mediaItem.put("file", "");
                            }
                        }
                        if (media.isLivePhoto() && media.getLiveVideo() != null) {
                            Object live = media.getLiveVideo();
                            String liveUrl = safe(live.getUrl());
                            mediaItem.put("liveVideoUrl", liveUrl);
                            if (withMedia && liveUrl.length() > 0) {
                                String liveName = "media/" + safe(post.getSnsId()) + "_" + j + "_live.mp4";
                                if (downloadTo(liveUrl, dir + "/" + liveName)) {
                                    mediaSaved++;
                                    mediaItem.put("liveVideoFile", liveName);
                                } else {
                                    mediaFailed++;
                                    mediaItem.put("liveVideoFile", "");
                                }
                            }
                        }
                        mediaJson.put(mediaItem);
                    }
                }
                item.put("media", mediaJson);
                postsJson.put(item);

                markdown.append("## ").append(timeText(post.getCreateTimeSeconds()))
                    .append(" · ").append(displayName(post)).append("\n\n");
                String text = safe(post.getText());
                if (text.length() > 0) markdown.append(text).append("\n\n");
                markdown.append("- 类型：").append(safe(post.getType()));
                if (mediaList != null && mediaList.size() > 0) {
                    markdown.append("，媒体 ").append(mediaList.size()).append(" 个");
                }
                markdown.append("\n\n");
                if (mediaList != null) {
                    for (int j = 0; j < mediaList.size(); j++) {
                        Object media = mediaList.get(j);
                        int mediaType = media.getType();
                        String ext = mediaType == 15 ? ".mp4" : ".jpg";
                        String fileName = "media/" + safe(post.getSnsId()) + "_" + j + ext;
                        if (withMedia) markdown.append("- `").append(fileName).append("`\n");
                        else markdown.append("- ").append(safe(media.getUrl())).append("\n");
                    }
                    markdown.append("\n");
                }
            } catch (Exception e) {
                log("导出第 " + i + " 条失败: " + e);
            }
            if ((i + 1) % 10 == 0) log("已处理 " + (i + 1) + "/" + posts.size());
        }

        org.json.JSONObject summary = new org.json.JSONObject();
        summary.put("exportTime", nowText());
        summary.put("self", safe(getLoginWxid()));
        summary.put("count", posts.size());
        summary.put("onlySelf", onlySelf);
        summary.put("mediaSaved", mediaSaved);
        summary.put("mediaFailed", mediaFailed);
        summary.put("posts", postsJson);
        writeText(dir + "/posts.json", summary.toString(2));
        writeText(dir + "/index.md", markdown.toString());

        log("导出完成：" + posts.size() + " 条，媒体 " + mediaSaved + " 个，失败 " + mediaFailed + " 个 → " + dir);
        toast("导出完成：" + posts.size() + " 条 → " + dir);
    } catch (Exception e) {
        log("导出失败: " + e);
        toast("导出失败：" + e);
    } finally {
        gRunning.set(false);
    }
}

// ── 设置（自绘 AlertDialog，不依赖脚本桥的对话框回调）──────────────────────
void openSettings() {
    android.app.Activity activity = currentActivityOrToast();
    if (activity == null) return;
    gActivity = activity;
    activity.runOnUiThread(new Runnable() {
        public void run() {
            showSettingsMenu((android.app.Activity) gActivity);
        }
    });
}

android.app.Activity currentActivityOrToast() {
    android.app.Activity activity = null;
    try {
        activity = getTopActivity();
    } catch (Exception e) {
        activity = null;
    }
    if (activity == null) toast("请先打开一个微信界面再进设置");
    return activity;
}

void showSettingsMenu(android.app.Activity activity) {
    java.util.List items = new java.util.ArrayList();
    items.add("导出数量：" + getInt("limit", 50) + " 条");
    items.add("范围：" + (getBoolean("only_self", true) ? "仅自己" : "全部可见缓存"));
    items.add("下载媒体：" + (getBoolean("download_media", true) ? "是" : "否"));
    items.add("输出目录：" + exportRoot());
    items.add("单个媒体上限：" + getInt("max_media_mb", 200) + " MB");
    items.add("立即导出");
    items.add("重置为默认设置");
    String[] labels = new String[items.size()];
    for (int i = 0; i < items.size(); i++) labels[i] = String.valueOf(items.get(i));
    new android.app.AlertDialog.Builder(activity)
        .setTitle("导出朋友圈")
        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                onMenu(which);
            }
        })
        .setNegativeButton("关闭", null)
        .show();
    log("openSettings -> custom menu shown, items=" + labels.length);
}

void onMenu(int index) {
    if (index == 0) {
        askNumber("导出数量", "1 - 500，按最近发布时间取", "limit", "50");
    } else if (index == 1) {
        putBoolean("only_self", !getBoolean("only_self", true));
        toast(getBoolean("only_self", true) ? "仅导出自己" : "导出全部可见缓存");
        openSettings();
    } else if (index == 2) {
        putBoolean("download_media", !getBoolean("download_media", true));
        toast(getBoolean("download_media", true) ? "已开启媒体下载" : "已关闭媒体下载");
        openSettings();
    } else if (index == 3) {
        askText("输出目录", "留空使用默认：/storage/emulated/0/Download/WCX朋友圈", "output_dir", "可留空");
    } else if (index == 4) {
        askNumber("单个媒体上限（MB）", "超过该大小的图片/视频会跳过，0 表示不限制", "max_media_mb", "200");
    } else if (index == 5) {
        startExport();
    } else if (index == 6) {
        putInt("limit", 50);
        putBoolean("only_self", true);
        putBoolean("download_media", true);
        putString("output_dir", "");
        putInt("max_media_mb", 200);
        toast("已恢复默认设置");
        openSettings();
    }
}

void askText(String title, String summary, String key, String placeholder) {
    askInput(title, summary, key, placeholder, false);
}

void askNumber(String title, String summary, String key, String placeholder) {
    askInput(title, summary, key, placeholder, true);
}

void askInput(String title, String summary, String key, String placeholder, boolean number) {
    android.app.Activity activity = currentActivityOrToast();
    if (activity == null) return;
    android.widget.EditText input = new android.widget.EditText(activity);
    input.setText(getString(key, ""));
    input.setHint(placeholder);
    input.setSingleLine(false);
    new android.app.AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(summary)
        .setView(input)
        .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                String text = input.getText().toString().trim();
                if (number) {
                    if (text.length() == 0) {
                        toast("未修改");
                        return;
                    }
                    try {
                        putInt(key, Integer.parseInt(text));
                        toast("已保存");
                    } catch (Exception e) {
                        toast("请输入数字");
                    }
                } else {
                    putString(key, text);
                    toast("已保存");
                }
            }
        })
        .setNegativeButton("取消", null)
        .show();
    log("input dialog shown: " + key);
}

// ── 文件与网络 ──────────────────────────────────────────────────────────────
String exportRoot() {
    String configured = getString("output_dir", "").trim();
    if (configured.length() > 0) return configured;
    String base = "/storage/emulated/0/Download/WCX朋友圈";
    java.io.File dir = new java.io.File(base);
    if (dir.isDirectory() || dir.mkdirs()) return base;
    return pluginDir + "/export";
}

boolean downloadTo(String url, String path) {
    if (url == null || url.length() == 0) return false;
    int maxMb = getInt("max_media_mb", 200);
    long limit = maxMb <= 0 ? Long.MAX_VALUE : maxMb * 1024L * 1024L;
    java.net.HttpURLConnection conn = null;
    java.io.InputStream in = null;
    java.io.FileOutputStream out = null;
    java.io.File target = null;
    boolean success = false;
    try {
        conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
        conn.setRequestProperty("Referer", "https://wx.qq.com/");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            log("下载失败 HTTP " + code + " " + truncate(url, 80));
            return false;
        }
        long declared = conn.getContentLengthLong();
        if (declared > limit) {
            log("媒体超过 " + maxMb + "MB，跳过：" + truncate(url, 80));
            return false;
        }
        target = new java.io.File(path);
        java.io.File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        in = conn.getInputStream();
        out = new java.io.FileOutputStream(target);
        byte[] buffer = new byte[8192];
        int n;
        long written = 0L;
        while ((n = in.read(buffer)) > 0) {
            written += n;
            if (written > limit) {
                log("媒体超过 " + maxMb + "MB，已中断：" + truncate(url, 80));
                return false;
            }
            out.write(buffer, 0, n);
        }
        success = true;
        return true;
    } catch (Exception e) {
        log("下载异常 " + truncate(url, 80) + " : " + e);
        return false;
    } finally {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (!success && target != null && target.isFile()) target.delete();
    }
}

void writeText(String path, String text) {
    java.io.OutputStreamWriter writer = null;
    try {
        java.io.File target = new java.io.File(path);
        java.io.File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(target), "UTF-8");
        writer.write(text);
        writer.flush();
    } catch (Exception e) {
        log("写入文件失败 " + path + " : " + e);
    } finally {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}

// ── 工具函数 ────────────────────────────────────────────────────────────────
String nowText() {
    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .format(new java.util.Date());
}

String timeText(long seconds) {
    if (seconds <= 0L) return "未知时间";
    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .format(new java.util.Date(seconds * 1000L));
}

String displayName(Object post) {
    String name = safe(post.getDisplayName());
    if (name.length() == 0) name = safe(post.getUserName());
    return name.length() == 0 ? "未知" : name;
}

String safe(String value) {
    return value == null ? "" : value;
}

String truncate(String value, int max) {
    if (value == null) return "";
    if (value.length() <= max) return value;
    return value.substring(0, max) + "…";
}
