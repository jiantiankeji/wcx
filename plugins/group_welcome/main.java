/*
 * WCX 脚本插件 · 新成员进群欢迎
 * ---------------------------------------------------------------------------
 * 监听群成员变动（join / left），自动发送带真实 @ 的欢迎语。
 *
 * 「真实 @」依赖脚本运行时对 [AtWx=wxid] 占位符的解析：
 *   sendText(群, "[AtWx=wxid]欢迎…")  →  微信里显示为 "@昵称 欢迎…"
 *   并写入 msgsource.atuserlist，对方会收到“有人@我”提醒。
 *
 * 支持：
 *   - 自定义模板（{name} / {group} / {wxid}）
 *   - 只对指定群生效
 *   - 延迟发送（避开秒退/广告号）
 *   - 10 分钟内同一人只欢迎一次
 *   - 每分钟最多触发次数，防止被拉群轰炸
 */

Object gSent;
Object gActivity;
long gWindowStart = 0L;
int gWindowCount = 0;

void onLoad() {
    gSent = new java.util.HashMap();
    log("已加载，欢迎语=" + truncate(getString("join_template", "欢迎 {name} 加入本群！"), 40));
}

void onUnload() {
    log("已卸载");
}

// ── 成员变动 ────────────────────────────────────────────────────────────────
void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    if (!getBoolean("enabled", true)) return;
    if (groupWxid == null || groupWxid.length() == 0) return;
    if (userWxid == null || userWxid.length() == 0) return;
    if (!groupAllowed(groupWxid)) return;

    if ("join".equals(type)) {
        handleJoin(groupWxid, userWxid, userName);
    } else if ("left".equals(type)) {
        handleLeft(groupWxid, userWxid, userName);
    }
}

void handleJoin(String groupWxid, String userWxid, String userName) {
    if (getBoolean("skip_self", true) && userWxid.equals(safe(getLoginWxid()))) return;

    String template = getString("join_template", "欢迎 {name} 加入本群，请先看群公告～");
    if (template.trim().length() == 0) return;

    if (!allowRate()) return;
    if (isDuplicate("join", groupWxid, userWxid)) return;

    String name = resolveName(groupWxid, userWxid, userName);
    String text = render(template, groupWxid, userWxid, name);
    boolean at = getBoolean("at_member", true);
    String message = at ? "[AtWx=" + userWxid + "]" + text : text;
    sendDelayed(groupWxid, message);
    log("欢迎新成员 " + name + "（" + userWxid + "）in " + groupWxid);
}

void handleLeft(String groupWxid, String userWxid, String userName) {
    String template = getString("leave_template", "");
    if (template.trim().length() == 0) return;
    if (!allowRate()) return;
    if (isDuplicate("left", groupWxid, userWxid)) return;

    String name = resolveName(groupWxid, userWxid, userName);
    sendDelayed(groupWxid, render(template, groupWxid, userWxid, name));
}

void sendDelayed(String talker, String text) {
    int delayMs = getInt("delay_ms", 1200);
    if (delayMs <= 0) {
        sendNow(talker, text);
        return;
    }
    delay(delayMs, new Runnable() {
        public void run() {
            sendNow(talker, text);
        }
    });
}

void sendNow(String talker, String text) {
    try {
        boolean ok = sendText(talker, text);
        if (!ok) log("欢迎语发送失败: " + talker);
    } catch (Exception e) {
        log("欢迎语发送异常: " + e);
    }
}

// ── 模板与过滤 ──────────────────────────────────────────────────────────────
String render(String template, String groupWxid, String userWxid, String name) {
    String text = template;
    text = text.replace("{name}", name);
    text = text.replace("{wxid}", userWxid);
    text = text.replace("{group}", safe(getGroupName(groupWxid)));
    return text;
}

String resolveName(String groupWxid, String userWxid, String userName) {
    String name = safe(userName).trim();
    if (name.length() > 0) return name;
    try {
        name = safe(getGroupMemberName(groupWxid, userWxid)).trim();
    } catch (Exception e) {
        name = "";
    }
    if (name.length() == 0) {
        try {
            name = safe(getFriendName(userWxid)).trim();
        } catch (Exception e) {
            name = "";
        }
    }
    return name.length() == 0 ? userWxid : name;
}

boolean groupAllowed(String groupWxid) {
    String groups = getString("groups", "").trim();
    if (groups.length() == 0) return true;
    String[] parts = groups.split(",");
    for (int i = 0; i < parts.length; i++) {
        if (parts[i].trim().equals(groupWxid)) return true;
    }
    return false;
}

boolean isDuplicate(String kind, String groupWxid, String userWxid) {
    String key = kind + "|" + groupWxid + "|" + userWxid;
    long now = System.currentTimeMillis();
    Object last = gSent.get(key);
    if (last != null && now - ((Long) last).longValue() < 10 * 60 * 1000L) return true;
    if (gSent.size() > 1024) gSent.clear();
    gSent.put(key, Long.valueOf(now));
    return false;
}

boolean allowRate() {
    int max = getInt("max_per_minute", 5);
    if (max <= 0) return true;
    long now = System.currentTimeMillis();
    if (now - gWindowStart > 60000L) {
        gWindowStart = now;
        gWindowCount = 0;
    }
    if (gWindowCount >= max) {
        log("每分钟触发次数已达上限(" + max + ")，跳过本次欢迎");
        return false;
    }
    gWindowCount++;
    return true;
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
    items.add("入群欢迎语：" + truncate(getString("join_template", "欢迎 {name} 加入本群，请先看群公告～"), 28));
    items.add("退群提示语：" + emptyLabel(getString("leave_template", "")));
    items.add("生效群：" + emptyLabel(getString("groups", "")));
    items.add("@ 新成员：" + (getBoolean("at_member", true) ? "开" : "关"));
    items.add("发送延迟：" + getInt("delay_ms", 1200) + " ms");
    items.add("每分钟上限：" + getInt("max_per_minute", 5) + " 次");
    items.add("跳过自己：" + (getBoolean("skip_self", true) ? "开" : "关"));
    items.add("向当前聊天发送测试欢迎语");
    String[] labels = new String[items.size()];
    for (int i = 0; i < items.size(); i++) labels[i] = String.valueOf(items.get(i));
    new android.app.AlertDialog.Builder(activity)
        .setTitle("新成员进群欢迎")
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
        askText(
            "入群欢迎语",
            "可用占位符：{name} 昵称、{group} 群名、{wxid} wxid",
            "join_template",
            "欢迎 {name} 加入本群，请先看群公告～"
        );
    } else if (index == 1) {
        askText("退群提示语", "留空表示不发送", "leave_template", "可留空");
    } else if (index == 2) {
        askText("生效群", "逗号分隔的群 ID（xxx@chatroom）；留空表示所有群", "groups", "123@chatroom,456@chatroom");
    } else if (index == 3) {
        putBoolean("at_member", !getBoolean("at_member", true));
        toast(getBoolean("at_member", true) ? "已开启 @" : "已关闭 @");
        openSettings();
    } else if (index == 4) {
        askNumber("发送延迟（毫秒）", "建议 800 - 3000，避开秒退账号", "delay_ms", "1200");
    } else if (index == 5) {
        askNumber("每分钟上限", "0 表示不限制", "max_per_minute", "5");
    } else if (index == 6) {
        putBoolean("skip_self", !getBoolean("skip_self", true));
        toast(getBoolean("skip_self", true) ? "已跳过自己" : "不跳过自己");
        openSettings();
    } else if (index == 7) {
        sendTest();
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

void sendTest() {
    String talker = safe(getTargetTalker());
    if (talker.length() == 0) {
        toast("请先打开一个聊天窗口");
        return;
    }
    String template = getString("join_template", "欢迎 {name} 加入本群，请先看群公告～");
    String text = render(template, talker, "wxid_test", "测试成员");
    sendText(talker, text);
    toast("已发送测试欢迎语");
}

// ── 工具函数 ────────────────────────────────────────────────────────────────
String safe(String value) {
    return value == null ? "" : value;
}

String truncate(String value, int max) {
    if (value == null) return "";
    if (value.length() <= max) return value;
    return value.substring(0, max) + "…";
}

String emptyLabel(String value) {
    return value == null || value.trim().length() == 0 ? "未设置（全部群）" : value.trim();
}
