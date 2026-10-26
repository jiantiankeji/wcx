/*
 * WCX 脚本插件 · 阿里云智能体回复
 * ---------------------------------------------------------------------------
 * 用阿里云百炼（DashScope）的「智能体应用」或 OpenAI 兼容模型自动回复微信消息。
 *
 * 两种接入模式：
 *   app    ：POST {endpoint}/api/v1/apps/{appId}/completion   （百炼应用，推荐）
 *   openai ：POST {endpoint}/compatible-mode/v1/chat/completions（qwen 等模型）
 *
 * 触发方式：仅私聊 / 群聊里 @ 我 / 全部 / 关闭，可再叠加会话白名单。
 * 所有请求都在独立后台线程串行执行，并带冷却时间，避免刷屏和限流。
 */

// ── 运行状态 ────────────────────────────────────────────────────────────────
Object gQueue;
Object gWorker;
Object gStop;
Object gCooldown;
Object gHistory;
Object gSecond;
Object gActivity;

void onLoad() {
    gStop = new java.util.concurrent.atomic.AtomicBoolean(false);
    gQueue = new java.util.concurrent.ArrayBlockingQueue(64);
    gCooldown = new java.util.HashMap();
    gHistory = new java.util.HashMap();
    gSecond = java.util.concurrent.TimeUnit.SECONDS;
    gWorker = new Thread(new Runnable() {
        public void run() {
            replyLoop();
        }
    }, "wcx-aliyun-reply");
    gWorker.setDaemon(true);
    gWorker.start();
    log("已加载，模式=" + getString("api_mode", "app") + "，触发=" + getString("trigger", "private"));
}

void onUnload() {
    Object stop = gStop;
    Object worker = gWorker;
    if (stop != null) stop.set(true);
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
    log("已卸载");
}

// ── 消息入口 ────────────────────────────────────────────────────────────────
void onHandleMsg(Object msg) {
    if (!getBoolean("enabled", true)) return;
    try {
        if (msg.isSend()) return;
        if (!msg.isText()) return;

        String talker = safe(msg.getTalker());
        if (talker.length() == 0) return;
        if (!matchTalker(msg, talker)) return;
        if (isCooling(talker)) return;

        String prompt = buildPrompt(msg);
        if (prompt.length() == 0) return;

        if (gQueue.size() >= 60) {
            log("待回复队列过长，丢弃一条来自 " + talker + " 的消息");
            return;
        }
        Object[] task = new Object[5];
        task[0] = talker;
        task[1] = safe(msg.getSendTalker());
        task[2] = prompt;
        task[3] = Boolean.valueOf(msg.isGroupChat());
        task[4] = "reply";
        if (!gQueue.offer(task)) {
            log("队列已满，丢弃一条消息");
            return;
        }
        markCooldown(talker);
    } catch (Exception e) {
        log("处理消息失败: " + e);
    }
}

// ── 触发规则 ────────────────────────────────────────────────────────────────
boolean matchTalker(Object msg, String talker) {
    String blacklist = getString("blacklist", "").trim();
    if (blacklist.length() > 0 && inList(blacklist, talker)) return false;

    String whitelist = getString("whitelist", "").trim();
    if (whitelist.length() > 0 && !inList(whitelist, talker)) return false;

    String trigger = getString("trigger", "private");
    boolean group = msg.isGroupChat();
    if ("off".equals(trigger)) return false;
    if ("private".equals(trigger) && group) return false;
    if ("at".equals(trigger)) {
        if (!group) return false;
        if (!msg.isAtMe()) return false;
    }
    return true;
}

String buildPrompt(Object msg) {
    String content = safe(msg.getContent());
    String prefix = getString("prefix", "").trim();
    if (prefix.length() > 0) {
        if (!content.startsWith(prefix)) return "";
        content = content.substring(prefix.length()).trim();
    }
    if (msg.isGroupChat() && content.startsWith("@")) {
        String marker = String.valueOf((char) 0x2005);
        int index = content.indexOf(marker);
        if (index > 0 && index < 64) content = content.substring(index + marker.length()).trim();
    }
    return truncate(content, 2000);
}

// ── 后台回复循环 ────────────────────────────────────────────────────────────
void replyLoop() {
    while (!gStop.get()) {
        Object item = null;
        try {
            item = gQueue.poll(1L, gSecond);
        } catch (Exception e) {
            item = null;
        }
        if (item == null) continue;
        handleTask((Object[]) item);
    }
}

void handleTask(Object[] task) {
    String talker = (String) task[0];
    String sender = (String) task[1];
    String prompt = (String) task[2];
    boolean group = ((Boolean) task[3]).booleanValue();
    String kind = task.length > 4 ? String.valueOf(task[4]) : "reply";

    if ("test".equals(kind)) {
        try {
            String reply = cleanReply(callAgent(talker, prompt));
            toast("测试成功：" + truncate(reply, 80));
        } catch (Exception e) {
            toast("测试失败：" + truncate(String.valueOf(e.getMessage()), 100));
        }
        return;
    }

    try {
        String reply = callAgent(talker, prompt);
        reply = cleanReply(reply);
        if (reply.length() == 0) {
            log("模型返回空回复: " + talker);
            return;
        }
        int maxLen = getInt("max_reply_len", 500);
        if (maxLen > 0 && reply.length() > maxLen) reply = reply.substring(0, maxLen) + "…";

        int delayMs = getInt("reply_delay_ms", 800);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (Exception ignored) {
            }
        }

        String prefix = (group && sender.length() > 0) ? "[AtWx=" + sender + "]" : "";
        boolean ok = sendText(talker, prefix + reply);
        log((ok ? "已回复 " : "回复失败 ") + talker + "：" + truncate(reply, 60));
    } catch (Exception e) {
        log("回复失败: " + e);
    }
}

// ── 模型调用 ────────────────────────────────────────────────────────────────
String callAgent(String talker, String prompt) {
    if ("openai".equals(getString("api_mode", "app"))) return callOpenAi(talker, prompt);
    return callDashScopeApp(talker, prompt);
}

String callDashScopeApp(String talker, String prompt) {
    String appId = getString("app_id", "").trim();
    String apiKey = getString("api_key", "").trim();
    if (appId.length() == 0 || apiKey.length() == 0) {
        throw new RuntimeException("未配置 App ID 或 API Key");
    }
    String endpoint = trimEnd(getString("endpoint", "https://dashscope.aliyuncs.com"), "/");
    String url = endpoint + "/api/v1/apps/" + appId + "/completion";

    String persona = getString("persona", "").trim();
    String fullPrompt = persona.length() > 0 ? persona + "\n\n用户说：" + prompt : prompt;

    org.json.JSONObject body = new org.json.JSONObject();
    org.json.JSONObject input = new org.json.JSONObject();
    input.put("prompt", fullPrompt);
    if (getBoolean("keep_session", true)) input.put("session_id", sessionId(talker));
    body.put("input", input);

    org.json.JSONObject parameters = new org.json.JSONObject();
    parameters.put("enable_thinking", false);
    parameters.put("has_thoughts", false);
    body.put("parameters", parameters);

    String response = httpPostJson(
        url,
        body.toString(),
        apiKey,
        getString("workspace", "").trim(),
        getInt("timeout_seconds", 60)
    );
    org.json.JSONObject root = new org.json.JSONObject(response);
    org.json.JSONObject output = root.optJSONObject("output");
    if (output != null) {
        String text = output.optString("text", "");
        if (text != null && text.trim().length() > 0) return text.trim();
    }
    String message = root.optString("message", "");
    throw new RuntimeException("响应缺少 output.text " + truncate(message.length() > 0 ? message : response, 200));
}

String callOpenAi(String talker, String prompt) {
    String apiKey = getString("api_key", "").trim();
    if (apiKey.length() == 0) throw new RuntimeException("未配置 API Key");
    String url = getString(
        "openai_url",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    ).trim();
    String model = getString("model", "qwen-plus").trim();

    org.json.JSONArray messages = new org.json.JSONArray();
    String persona = getString("persona", "").trim();
    if (persona.length() > 0) {
        messages.put(new org.json.JSONObject().put("role", "system").put("content", persona));
    }
    java.util.List history = historyFor(talker);
    for (int i = 0; i < history.size(); i++) {
        String[] turn = (String[]) history.get(i);
        messages.put(new org.json.JSONObject().put("role", turn[0]).put("content", turn[1]));
    }
    messages.put(new org.json.JSONObject().put("role", "user").put("content", prompt));

    org.json.JSONObject body = new org.json.JSONObject();
    body.put("model", model);
    body.put("messages", messages);
    body.put("temperature", 0.7);
    body.put("max_tokens", getInt("max_tokens", 800));

    String response = httpPostJson(
        url,
        body.toString(),
        apiKey,
        "",
        getInt("timeout_seconds", 60)
    );
    org.json.JSONObject root = new org.json.JSONObject(response);
    org.json.JSONArray choices = root.optJSONArray("choices");
    if (choices == null || choices.length() == 0) {
        throw new RuntimeException("响应缺少 choices: " + truncate(response, 200));
    }
    String text = choices.getJSONObject(0).getJSONObject("message").optString("content", "");
    if (text != null && text.trim().length() > 0) {
        remember(talker, prompt, text.trim());
        return text.trim();
    }
    throw new RuntimeException("响应内容为空");
}

// ── HTTP ────────────────────────────────────────────────────────────────────
String httpPostJson(String url, String body, String apiKey, String workspace, int timeoutSeconds) {
    java.net.HttpURLConnection conn = null;
    try {
        java.net.URL target = new java.net.URL(url);
        conn = (java.net.HttpURLConnection) target.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("User-Agent", "WCX-Plugin/aliyun_agent_reply");
        if (workspace != null && workspace.length() > 0 && !"default".equals(workspace)) {
            conn.setRequestProperty("X-DashScope-WorkSpace", workspace);
        }

        byte[] payload = body.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(payload.length);
        java.io.OutputStream out = conn.getOutputStream();
        try {
            out.write(payload);
            out.flush();
        } finally {
            out.close();
        }

        int code = conn.getResponseCode();
        String response = readResponse(conn, code);
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + " " + truncate(response, 300));
        }
        return response;
    } catch (RuntimeException e) {
        throw e;
    } catch (Exception e) {
        throw new RuntimeException("请求失败: " + e);
    } finally {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
    }
}

String readResponse(java.net.HttpURLConnection conn, int code) {
    java.io.InputStream in = null;
    try {
        in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) return "";
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
        return new String(buffer.toByteArray(), "UTF-8");
    } catch (Exception e) {
        return "";
    } finally {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}

// ── 冷却 / 会话记忆 ─────────────────────────────────────────────────────────
boolean isCooling(String talker) {
    int seconds = getInt("cooldown_seconds", 3);
    if (seconds <= 0) return false;
    Object last = gCooldown.get(talker);
    if (last == null) return false;
    long elapsed = System.currentTimeMillis() - ((Long) last).longValue();
    return elapsed < seconds * 1000L;
}

void markCooldown(String talker) {
    if (gCooldown.size() > 512) gCooldown.clear();
    gCooldown.put(talker, Long.valueOf(System.currentTimeMillis()));
}

java.util.List historyFor(String talker) {
    Object value = gHistory.get(talker);
    if (value == null) {
        value = new java.util.ArrayList();
        gHistory.put(talker, value);
    }
    return (java.util.List) value;
}

void remember(String talker, String userText, String replyText) {
    java.util.List history = historyFor(talker);
    history.add(new String[]{"user", userText});
    history.add(new String[]{"assistant", replyText});
    while (history.size() > 12) history.remove(history.get(0));
    if (gHistory.size() > 256) gHistory.clear();
}

String sessionId(String talker) {
    return "wcx" + Integer.toHexString(talker.hashCode());
}

String cleanReply(String text) {
    if (text == null) return "";
    String value = text.trim();
    if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1).trim();
    }
    return value;
}

// ── 设置界面（自绘 AlertDialog，不依赖脚本桥的对话框回调）──────────────────
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
    items.add("接入模式：" + modeLabel());
    items.add("App ID：" + emptyLabel(getString("app_id", "")));
    items.add("API Key：" + maskSecret(getString("api_key", "")));
    items.add("接入点：" + getString("endpoint", "https://dashscope.aliyuncs.com"));
    items.add("模型（OpenAI 兼容）：" + getString("model", "qwen-plus"));
    items.add("触发方式：" + triggerLabel());
    items.add("会话白名单：" + emptyLabel(getString("whitelist", "")));
    items.add("人设 / 前缀提示词：" + truncate(emptyLabel(getString("persona", "")), 30));
    items.add("冷却时间：" + getInt("cooldown_seconds", 3) + " 秒");
    items.add("最大回复长度：" + getInt("max_reply_len", 500) + " 字");
    items.add("会话记忆（OpenAI 模式）：" + yesNo(getBoolean("keep_session", true)));
    items.add("测试一次智能体调用");
    String[] labels = new String[items.size()];
    for (int i = 0; i < items.size(); i++) labels[i] = String.valueOf(items.get(i));
    new android.app.AlertDialog.Builder(activity)
        .setTitle("阿里云智能体回复")
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
        askMode();
    } else if (index == 1) {
        askText("App ID", "百炼应用 ID（形如 xxxxxxxx-xxxx-xxxx）", "app_id", "");
    } else if (index == 2) {
        askText("API Key", "DashScope API Key（sk-...）", "api_key", "sk-...");
    } else if (index == 3) {
        askText("接入点", "默认 https://dashscope.aliyuncs.com；私有网关可改", "endpoint", "https://dashscope.aliyuncs.com");
    } else if (index == 4) {
        askText("模型名称", "OpenAI 兼容模式使用，例如 qwen-plus / qwen-max", "model", "qwen-plus");
    } else if (index == 5) {
        askTrigger();
    } else if (index == 6) {
        askText("会话白名单", "逗号分隔的 wxid / xxx@chatroom；留空表示不限制", "whitelist", "wxid_1,123@chatroom");
    } else if (index == 7) {
        askText("人设 / 前缀提示词", "会拼在用户消息前（app 模式）或作为 system（OpenAI 模式）", "persona", "你是一个简洁、礼貌的微信助手");
    } else if (index == 8) {
        askNumber("冷却时间（秒）", "同一会话两次自动回复的最小间隔，0 表示不限制", "cooldown_seconds", "3");
    } else if (index == 9) {
        askNumber("最大回复长度", "超出会被截断，0 表示不限制", "max_reply_len", "500");
    } else if (index == 10) {
        putBoolean("keep_session", !getBoolean("keep_session", true));
        toast(getBoolean("keep_session", true) ? "已开启会话记忆" : "已关闭会话记忆");
        openSettings();
    } else if (index == 11) {
        testAgent();
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

void askMode() {
    android.app.Activity activity = currentActivityOrToast();
    if (activity == null) return;
    String[] options = new String[]{"百炼智能体应用（app）", "OpenAI 兼容模型（openai）"};
    int checked = "openai".equals(getString("api_mode", "app")) ? 1 : 0;
    new android.app.AlertDialog.Builder(activity)
        .setTitle("接入模式")
        .setSingleChoiceItems(options, checked, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                putString("api_mode", which == 1 ? "openai" : "app");
                toast("已切换为 " + modeLabel());
            }
        })
        .setNegativeButton("取消", null)
        .show();
}

void askTrigger() {
    android.app.Activity activity = currentActivityOrToast();
    if (activity == null) return;
    String[] options = new String[]{"仅私聊", "群聊里 @ 我", "所有会话", "关闭自动回复"};
    String current = getString("trigger", "private");
    int checked = 0;
    if ("at".equals(current)) checked = 1;
    else if ("all".equals(current)) checked = 2;
    else if ("off".equals(current)) checked = 3;
    new android.app.AlertDialog.Builder(activity)
        .setTitle("触发方式")
        .setSingleChoiceItems(options, checked, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                String trigger = "private";
                if (which == 1) trigger = "at";
                if (which == 2) trigger = "all";
                if (which == 3) trigger = "off";
                putString("trigger", trigger);
                toast("已保存：" + triggerLabel());
            }
        })
        .setNegativeButton("取消", null)
        .show();
}

void testAgent() {
    Object[] task = new Object[5];
    task[0] = "wcx-test";
    task[1] = "";
    task[2] = "你好，请用一句话介绍你自己。";
    task[3] = Boolean.FALSE;
    task[4] = "test";
    if (!gQueue.offer(task)) {
        toast("待回复队列已满，稍后再试");
        return;
    }
    toast("测试请求已加入队列…");
}

// ── 工具函数 ────────────────────────────────────────────────────────────────
boolean inList(String csv, String value) {
    if (csv == null || csv.length() == 0 || value == null) return false;
    String[] parts = csv.split(",");
    for (int i = 0; i < parts.length; i++) {
        if (parts[i].trim().equals(value)) return true;
    }
    return false;
}

String safe(String value) {
    return value == null ? "" : value;
}

String truncate(String value, int max) {
    if (value == null) return "";
    if (value.length() <= max) return value;
    return value.substring(0, max) + "…";
}

String trimEnd(String value, String suffix) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith(suffix)) result = result.substring(0, result.length() - suffix.length());
    return result;
}

String maskSecret(String secret) {
    if (secret == null || secret.length() == 0) return "未配置";
    if (secret.length() <= 8) return "****";
    return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
}

String emptyLabel(String value) {
    return value == null || value.trim().length() == 0 ? "未配置" : value.trim();
}

String yesNo(boolean value) {
    return value ? "开" : "关";
}

String modeLabel() {
    return "openai".equals(getString("api_mode", "app")) ? "OpenAI 兼容模型" : "百炼智能体应用";
}

String triggerLabel() {
    String trigger = getString("trigger", "private");
    if ("at".equals(trigger)) return "群聊里 @ 我";
    if ("all".equals(trigger)) return "所有会话";
    if ("off".equals(trigger)) return "关闭";
    return "仅私聊";
}
