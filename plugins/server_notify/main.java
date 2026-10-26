/*
 * WCX 脚本插件 · 服务器通知转发（卡片）
 * ---------------------------------------------------------------------------
 * 手机上的微信进程无法被公网服务器反向连接，因此由插件主动与服务器建立
 * WebSocket 长连接；服务器下发通知后，插件把通知以「卡片」形式转发到指定群聊。
 *
 * 协议（与内置 WcxNotifyClient / jzyh_backend 保持一致）：
 *   连接：ws(s)://<host>/ws/wcx-notify?token=<共享令牌>
 *   上报：{"type":"hello","wxId":"..."}
 *   心跳：{"type":"ping"}  →  服务端 {"type":"pong"}
 *   下发：{"type":"notify","title":"...","content":"...","cardTitle":"...",
 *          "cardDesc":"...","link":"...","convId":"xxx@chatroom","id":1,
 *          "path":"...","thumbUrl":"..."}
 *
 * 转发策略：
 *   - 目标群：本地配置优先；未配置时回退服务端下发的 convId
 *   - 卡片形式：链接卡片（默认）/ 纯文本 / 小程序卡片（需粘贴真实卡片模板）
 *   - 卡片发送失败自动回退文本，避免通知丢失
 *
 * 说明：插件内置一个精简 WebSocket 客户端（握手 / 帧编解码 / 心跳 / 断线重连），
 * 不依赖 OkHttp，wss 走系统 SSLSocket 并校验证书主机名。
 */

// ── 运行状态 ────────────────────────────────────────────────────────────────
Object gStop;
Object gThread;
Object gSocket;
Object gIn;
Object gOut;
Object gSendLock;
Object gRandom;
Object gSeen;
Object gStatus;
Object gActivity;
long gBackoff = 1000L;

void onLoad() {
    gStop = new java.util.concurrent.atomic.AtomicBoolean(false);
    gSendLock = new Object();
    gRandom = new java.security.SecureRandom();
    gSeen = new java.util.HashMap();
    gStatus = new java.util.concurrent.atomic.AtomicReference("未启动");
    gThread = new Thread(new Runnable() {
        public void run() {
            supervisorLoop();
        }
    }, "wcx-server-notify");
    gThread.setDaemon(true);
    gThread.start();
    log("已启动，服务器 = " + getString("server_url", ""));
}

void onUnload() {
    Object stop = gStop;
    Object thread = gThread;
    if (stop != null) stop.set(true);
    closeSocket();
    // 等旧线程真正退出后再让 onLoad 重置全局变量，否则旧线程会读到新实例的状态。
    if (thread != null) {
        try {
            ((Thread) thread).interrupt();
        } catch (Exception ignored) {
        }
        try {
            ((Thread) thread).join(2000L);
        } catch (Exception ignored) {
        }
    }
    log("已卸载");
}

// ── 连接监督 ────────────────────────────────────────────────────────────────
void supervisorLoop() {
    while (!gStop.get()) {
        String serverUrl = getString("server_url", "").trim();
        if (serverUrl.length() == 0) {
            setStatus("未配置服务器地址");
            sleepQuietly(5000L);
            continue;
        }
        try {
            connectAndServe();
        } catch (Throwable e) {
            if (!gStop.get()) {
                setStatus("连接失败：" + shortError(e));
                log("WS 连接异常: " + e);
            }
        }
        closeSocket();
        if (gStop.get()) break;
        sleepQuietly(gBackoff);
        gBackoff = Math.min(gBackoff * 2L, 60000L);
    }
    setStatus("已停止");
}

void sleepQuietly(long millis) {
    try {
        Thread.sleep(millis);
    } catch (Exception ignored) {
    }
}

void connectAndServe() {
    String[] parts = parseWsUrl(buildWsUrl());
    String scheme = parts[0];
    String host = parts[1];
    int port = Integer.parseInt(parts[2]);
    String path = parts[3];

    java.net.Socket socket = openSocket(scheme, host, port);
    gSocket = socket;
    socket.setTcpNoDelay(true);
    socket.setSoTimeout(pingTimeoutMs());
    gIn = socket.getInputStream();
    gOut = socket.getOutputStream();

    String key = newWsKey();
    writeHandshake(scheme, host, port, path, key);
    String headers = readHttpHeaders(gIn);
    int lineEnd = headers.indexOf('\n');
    String firstLine = lineEnd < 0 ? headers : headers.substring(0, lineEnd);
    if (firstLine.indexOf(" 101") < 0) {
        throw new RuntimeException("握手失败: " + firstLine.trim());
    }
    String accept = headerValue(headers, "sec-websocket-accept");
    String expected = sha1Base64(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    if (!expected.equals(accept)) {
        throw new RuntimeException("Sec-WebSocket-Accept 校验失败");
    }

    gBackoff = 1000L;
    setStatus("已连接 " + host + ":" + port);
    log("已连接 " + host + ":" + port + path);
    sendText(buildHelloJson());
    readLoop();
}

void readLoop() {
    while (!gStop.get()) {
        try {
            String text = readFrame();
            if (text != null) handleServerText(text);
        } catch (java.net.SocketTimeoutException timeout) {
            sendText("{\"type\":\"ping\"}");
            sendFrame(9, new byte[0]);
        }
    }
}

java.net.Socket openSocket(String scheme, String host, int port) {
    if ("wss".equals(scheme)) {
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) javax.net.ssl.SSLSocketFactory.getDefault().createSocket();
        ssl.connect(new java.net.InetSocketAddress(host, port), 15000);
        javax.net.ssl.SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }
    java.net.Socket socket = new java.net.Socket();
    socket.connect(new java.net.InetSocketAddress(host, port), 15000);
    return socket;
}

void closeSocket() {
    java.net.Socket socket = (java.net.Socket) gSocket;
    gSocket = null;
    gIn = null;
    gOut = null;
    if (socket != null) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}

// ── 握手 ────────────────────────────────────────────────────────────────────
void writeHandshake(String scheme, String host, int port, String path, String key) {
    String hostHeader = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    boolean defaultPort = ("wss".equals(scheme) && port == 443) || ("ws".equals(scheme) && port == 80);
    if (!defaultPort) hostHeader = hostHeader + ":" + port;
    StringBuilder request = new StringBuilder();
    request.append("GET ").append(path).append(" HTTP/1.1\r\n");
    request.append("Host: ").append(hostHeader).append("\r\n");
    request.append("Upgrade: websocket\r\n");
    request.append("Connection: Upgrade\r\n");
    request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
    request.append("Sec-WebSocket-Version: 13\r\n");
    request.append("User-Agent: WCX-Plugin/server_notify\r\n");
    request.append("\r\n");
    gOut.write(request.toString().getBytes("UTF-8"));
    gOut.flush();
}

String readHttpHeaders(Object input) {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    int a = -1;
    int b = -1;
    int c = -1;
    int d = -1;
    while (buffer.size() < 16384) {
        int value = ((java.io.InputStream) input).read();
        if (value < 0) throw new java.io.EOFException("握手响应被关闭");
        buffer.write(value);
        a = b;
        b = c;
        c = d;
        d = value;
        if (a == 13 && b == 10 && c == 13 && d == 10) break;
    }
    return new String(buffer.toByteArray(), "UTF-8");
}

String headerValue(String headers, String name) {
    String[] lines = headers.split("\r\n");
    for (int i = 0; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon <= 0) continue;
        if (lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
            return lines[i].substring(colon + 1).trim();
        }
    }
    return "";
}

String newWsKey() {
    byte[] nonce = new byte[16];
    gRandom.nextBytes(nonce);
    return java.util.Base64.getEncoder().encodeToString(nonce);
}

String sha1Base64(String text) {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
    byte[] hash = digest.digest(text.getBytes("UTF-8"));
    return java.util.Base64.getEncoder().encodeToString(hash);
}

// ── 帧编解码 ────────────────────────────────────────────────────────────────
String readFrame() {
    int b1 = gIn.read();
    if (b1 < 0) throw new java.io.EOFException("连接已关闭");
    int b2 = gIn.read();
    if (b2 < 0) throw new java.io.EOFException("连接已关闭");

    boolean fin = (b1 & 0x80) != 0;
    int opcode = b1 & 0x0F;
    boolean masked = (b2 & 0x80) != 0;
    long length = b2 & 0x7F;
    if (length == 126L) {
        byte[] ext = readN(2);
        length = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
    } else if (length == 127L) {
        byte[] ext = readN(8);
        length = 0L;
        for (int i = 0; i < 8; i++) length = (length << 8) | (ext[i] & 0xFF);
    }
    if (length < 0L || length > 4L * 1024L * 1024L) {
        throw new RuntimeException("WebSocket 帧过大: " + length);
    }
    byte[] mask = null;
    if (masked) mask = readN(4);
    byte[] payload = readN((int) length);
    if (masked) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
    }

    if (opcode == 8) {
        String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, "UTF-8") : "";
        throw new java.io.EOFException("服务端关闭连接 " + reason);
    }
    if (opcode == 9) {
        sendFrame(10, payload);
        return null;
    }
    if (opcode == 10) return null;
    if (opcode == 1) {
        if (!fin) {
            log("忽略分片文本帧");
            return null;
        }
        return new String(payload, "UTF-8");
    }
    return null;
}

byte[] readN(int size) {
    if (size <= 0) return new byte[0];
    byte[] buffer = new byte[size];
    int offset = 0;
    while (offset < size) {
        int read = gIn.read(buffer, offset, size - offset);
        if (read < 0) throw new java.io.EOFException("连接已关闭");
        offset += read;
    }
    return buffer;
}

void sendText(String text) {
    sendFrame(1, text.getBytes("UTF-8"));
}

void sendFrame(int opcode, byte[] payload) {
    if (payload == null) payload = new byte[0];
    synchronized (gSendLock) {
        if (gOut == null) throw new RuntimeException("连接尚未建立");
        java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        int length = payload.length;
        if (length <= 125) {
            frame.write(0x80 | length);
        } else if (length <= 65535) {
            frame.write(0x80 | 126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long value = length;
            for (int i = 7; i >= 0; i--) frame.write((int) ((value >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        gRandom.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < length; i++) {
            frame.write((payload[i] ^ mask[i % 4]) & 0xFF);
        }
        gOut.write(frame.toByteArray());
        gOut.flush();
    }
}

// ── 服务端消息 ──────────────────────────────────────────────────────────────
void handleServerText(String text) {
    org.json.JSONObject message;
    try {
        message = new org.json.JSONObject(text);
    } catch (Exception e) {
        log("忽略非 JSON 帧: " + truncate(text, 80));
        return;
    }
    String type = message.optString("type", "");
    if ("pong".equals(type)) return;
    if (!"notify".equals(type)) {
        log("忽略未知消息类型: " + type);
        return;
    }

    String id = message.optString("id", "");
    if (id.length() > 0 && isDuplicate(id)) {
        log("忽略重复通知: " + id);
        return;
    }

    String title = firstNonBlank(message.optString("cardTitle", ""), message.optString("title", ""), "系统通知");
    String desc = firstNonBlank(message.optString("cardDesc", ""), message.optString("content", ""), "");
    String link = message.optString("link", "");
    String convId = message.optString("convId", "");
    String path = message.optString("path", "");
    String thumbUrl = message.optString("thumbUrl", "");

    if (getBoolean("show_toast", true)) {
        toast("【" + title + "】" + truncate(desc.replace('\n', ' '), 60));
    }
    log("收到通知 #" + id + " " + title);
    forwardToGroups(id, title, desc, link, convId, path, thumbUrl);
}

void forwardToGroups(String id, String title, String desc, String link, String convId, String path, String thumbUrl) {
    java.util.List targets = targetGroups(convId);
    if (targets.isEmpty()) {
        log("收到通知但没有目标群（请在插件设置里配置群 ID）");
        setStatus("已连接（通知缺少目标群）");
        return;
    }

    String mode = getString("card_type", "link");
    byte[] thumb = null;
    if (!"text".equals(mode)) thumb = fetchThumb(thumbUrl);
    String textContent = buildText(title, desc, link);
    String pagePath = resolvePagePath(id, path);

    int success = 0;
    for (int i = 0; i < targets.size(); i++) {
        String group = String.valueOf(targets.get(i));
        boolean ok = false;
        if ("text".equals(mode)) {
            ok = safeSendText(group, textContent);
        } else if ("miniprogram".equals(mode)) {
            ok = sendMiniCard(group, title, desc, pagePath, thumbUrl);
        }
        if (!ok && !"text".equals(mode)) {
            ok = sendLinkCard(group, title, desc, link, thumb);
        }
        if (!ok && !"text".equals(mode)) {
            ok = safeSendText(group, textContent);
        }
        if (ok) {
            success++;
        } else {
            log("转发到 " + group + " 失败");
        }
        if (i + 1 < targets.size()) {
            try {
                Thread.sleep(getInt("send_interval_ms", 300));
            } catch (Exception ignored) {
            }
        }
    }
    setStatus("已连接（最近转发 " + success + "/" + targets.size() + " 个群）");
}

// ── 卡片 ────────────────────────────────────────────────────────────────────
boolean sendLinkCard(String group, String title, String desc, String link, byte[] thumb) {
    if (link == null || link.length() == 0) return false;
    try {
        return shareWebpage(group, title, desc, link, thumb, getString("app_id", ""));
    } catch (Throwable e) {
        log("链接卡片发送失败: " + e);
        return false;
    }
}

boolean sendMiniCard(String group, String title, String desc, String pagePath, String thumbUrl) {
    String template = getString("card_template", "").trim();
    if (template.length() == 0) {
        log("小程序卡片模板为空，回退为链接卡片");
        return false;
    }
    if (pagePath.length() == 0) {
        log("小程序卡片缺少页面路径，回退为链接卡片");
        return false;
    }
    try {
        String xml = applyCardTemplate(template, title, desc, pagePath, thumbUrl);
        return sendXmlMsg(group, xml);
    } catch (Throwable e) {
        log("小程序卡片发送失败: " + e);
        return false;
    }
}

String applyCardTemplate(String template, String title, String desc, String pagePath, String thumbUrl) {
    String xml = template;
    xml = replaceXmlTag(xml, "title", xmlEscape(title));
    xml = replaceXmlTag(xml, "des", xmlEscape(desc));
    xml = replaceXmlTag(xml, "pagepath", "<![CDATA[" + cdata(pagePath) + "]]>");
    if (thumbUrl != null && thumbUrl.length() > 0) {
        xml = replaceXmlTag(xml, "weapppagethumbrawurl", "<![CDATA[" + cdata(thumbUrl) + "]]>");
    }
    return xml;
}

String replaceXmlTag(String xml, String tag, String value) {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
        "(?s)<" + tag + ">.*?</" + tag + ">"
    );
    java.util.regex.Matcher matcher = pattern.matcher(xml);
    if (!matcher.find()) return xml;
    return matcher.replaceFirst(
        java.util.regex.Matcher.quoteReplacement("<" + tag + ">" + value + "</" + tag + ">")
    );
}

boolean safeSendText(String group, String content) {
    try {
        return sendText(group, content);
    } catch (Throwable e) {
        log("文本发送失败: " + e);
        return false;
    }
}

String buildText(String title, String desc, String link) {
    StringBuilder sb = new StringBuilder();
    sb.append("【").append(title).append("】");
    if (desc != null && desc.length() > 0) sb.append("\n").append(desc);
    if (link != null && link.length() > 0) sb.append("\n详情：").append(link);
    return sb.toString();
}

String resolvePagePath(String id, String serverPath) {
    String path = serverPath == null ? "" : serverPath.trim();
    if (path.length() > 0) return path;
    String template = getString("page_path_template", "").trim();
    if (template.length() == 0) return "";
    return template.replace("{id}", id == null ? "" : id);
}

// ── 缩略图 ──────────────────────────────────────────────────────────────────
byte[] fetchThumb(String url) {
    if (url == null || url.length() == 0) return null;
    java.net.HttpURLConnection connection = null;
    java.io.InputStream input = null;
    try {
        connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            log("封面下载失败 HTTP " + code);
            return null;
        }
        input = connection.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
            if (buffer.size() > 2 * 1024 * 1024) {
                log("封面超过 2MB，放弃");
                return null;
            }
        }
        byte[] data = buffer.toByteArray();
        if (data.length <= 30000) return data;
        return compressThumb(data);
    } catch (Throwable e) {
        log("封面获取失败: " + e);
        return null;
    } finally {
        if (input != null) {
            try {
                input.close();
            } catch (Exception ignored) {
            }
        }
        if (connection != null) connection.disconnect();
    }
}

byte[] compressThumb(byte[] data) {
    try {
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int max = 320;
        if (width > max) {
            height = height * max / width;
            width = max;
            bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true);
        }
        for (int quality = 80; quality >= 30; quality -= 10) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output);
            if (output.size() <= 30000) return output.toByteArray();
        }
    } catch (Throwable e) {
        log("封面压缩失败: " + e);
    }
    return null;
}

// ── 目标群 / 去重 ───────────────────────────────────────────────────────────
java.util.List targetGroups(String convId) {
    java.util.List result = new java.util.ArrayList();
    String csv = getString("groups", "").trim();
    if (csv.length() > 0) {
        String[] parts = csv.split("[,;\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String item = parts[i].trim();
            if (item.length() > 0 && !result.contains(item)) result.add(item);
        }
    }
    if (result.isEmpty() && convId != null && convId.trim().length() > 0) {
        result.add(convId.trim());
    }
    return result;
}

boolean isDuplicate(String id) {
    long now = System.currentTimeMillis();
    Object last = gSeen.get(id);
    if (last != null && now - ((Long) last).longValue() < 10 * 60 * 1000L) return true;
    if (gSeen.size() > 512) gSeen.clear();
    gSeen.put(id, Long.valueOf(now));
    return false;
}

// ── URL 处理 ────────────────────────────────────────────────────────────────
String buildWsUrl() {
    String base = getString("server_url", "").trim();
    if (base.length() == 0) throw new RuntimeException("未配置服务器地址");
    if (base.startsWith("https://")) {
        base = "wss://" + base.substring(8);
    } else if (base.startsWith("http://")) {
        base = "ws://" + base.substring(7);
    } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
        base = "wss://" + base;
    }
    String token = getString("token", "").trim();
    String query = "";
    int mark = base.indexOf('?');
    if (mark >= 0) {
        query = base.substring(mark);
        base = base.substring(0, mark);
    }
    if (!base.endsWith("/ws/wcx-notify")) {
        base = base + "/ws/wcx-notify";
    }
    if (query.length() == 0) {
        query = "?token=" + java.net.URLEncoder.encode(token, "UTF-8");
    } else if (query.indexOf("token=") < 0) {
        query = query + "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
    }
    return base + query;
}

String[] parseWsUrl(String raw) {
    String url = raw.trim();
    String scheme = "ws";
    if (url.startsWith("wss://")) {
        scheme = "wss";
        url = url.substring(6);
    } else if (url.startsWith("ws://")) {
        url = url.substring(5);
    }
    int slash = url.indexOf('/');
    String authority = slash < 0 ? url : url.substring(0, slash);
    String path = slash < 0 ? "/" : url.substring(slash);
    String host = authority;
    int port = "wss".equals(scheme) ? 443 : 80;
    if (authority.startsWith("[")) {
        int close = authority.indexOf(']');
        host = authority.substring(1, close);
        int colon = authority.indexOf(':', close);
        if (colon > 0) port = Integer.parseInt(authority.substring(colon + 1));
    } else {
        int colon = authority.lastIndexOf(':');
        if (colon > 0) {
            host = authority.substring(0, colon);
            port = Integer.parseInt(authority.substring(colon + 1));
        }
    }
    return new String[]{scheme, host, String.valueOf(port), path};
}

int pingTimeoutMs() {
    int seconds = getInt("ping_seconds", 30);
    if (seconds < 1) seconds = 1;
    return seconds * 1000;
}

String buildHelloJson() {
    String wxId = "";
    try {
        wxId = getLoginWxid();
    } catch (Exception e) {
        wxId = "";
    }
    return "{\"type\":\"hello\",\"wxId\":\"" + jsonEscape(wxId) + "\"}";
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
    items.add("服务器地址：" + emptyLabel(getString("server_url", "")));
    items.add("共享令牌：" + maskSecret(getString("token", "")));
    items.add("目标群：" + emptyLabel(getString("groups", "")));
    items.add("卡片形式：" + cardTypeLabel());
    items.add("小程序卡片模板：" + (getString("card_template", "").trim().length() > 0 ? "已配置" : "未配置"));
    items.add("页面路径模板：" + emptyLabel(getString("page_path_template", "")));
    items.add("本地 Toast 提示：" + (getBoolean("show_toast", true) ? "开" : "关"));
    items.add("查看连接状态");
    items.add("立即重连");
    CharSequence[] labels = new CharSequence[items.size()];
    for (int i = 0; i < items.size(); i++) labels[i] = String.valueOf(items.get(i));
    new android.app.AlertDialog.Builder(activity)
        .setTitle("服务器通知转发")
        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                onMenu(which);
            }
        })
        .setNegativeButton("关闭", null)
        .show();
    log("openSettings -> custom menu shown, items=" + labels.length + " status=" + getStatus());
}

void onMenu(int index) {
    log("onMenu index=" + index);
    if (index == 0) {
        askServerText("服务器地址", "支持 https://host、wss://host 或完整 ws 地址", "server_url", "https://notify.example.com");
    } else if (index == 1) {
        askServerText("共享令牌", "与服务端 WCX_NOTIFY_TOKEN 一致", "token", "可留空");
    } else if (index == 2) {
        askText("目标群", "逗号分隔的群 ID（xxx@chatroom）；留空则用服务端下发的 convId", "groups", "123@chatroom,456@chatroom");
    } else if (index == 3) {
        askCardType();
    } else if (index == 4) {
        askText(
            "小程序卡片模板 XML",
            "粘贴一条真实可打开的小程序卡片报文；留空则始终用链接卡片",
            "card_template",
            "<msg><appmsg ...>...</appmsg></msg>"
        );
    } else if (index == 5) {
        askText("页面路径模板", "卡片 type=33 使用；{id} 会替换为通知 ID", "page_path_template", "pages/post/post-detail?id={id}");
    } else if (index == 6) {
        putBoolean("show_toast", !getBoolean("show_toast", true));
        toast(getBoolean("show_toast", true) ? "已开启 Toast" : "已关闭 Toast");
        openSettings();
    } else if (index == 7) {
        toast("状态：" + getStatus());
        openSettings();
    } else if (index == 8) {
        closeSocket();
        toast("正在重连…");
    }
}

void askText(String title, String summary, String key, String placeholder) {
    askInput(title, summary, key, placeholder, false);
}

void askServerText(String title, String summary, String key, String placeholder) {
    askInput(title, summary, key, placeholder, true);
}

void askInput(String title, String summary, String key, String placeholder, boolean reconnect) {
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
                putString(key, input.getText().toString().trim());
                toast("已保存");
                if (reconnect) closeSocket();
            }
        })
        .setNegativeButton("取消", null)
        .show();
    log("input dialog shown: " + key);
}

void askCardType() {
    android.app.Activity activity = currentActivityOrToast();
    if (activity == null) return;
    String[] options = new String[]{"链接卡片（推荐）", "纯文本", "小程序卡片（需模板）"};
    int checked = 0;
    String current = getString("card_type", "link");
    if ("text".equals(current)) {
        checked = 1;
    } else if ("miniprogram".equals(current)) {
        checked = 2;
    }
    new android.app.AlertDialog.Builder(activity)
        .setTitle("卡片形式")
        .setSingleChoiceItems(options, checked, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                putString("card_type", which == 1 ? "text" : (which == 2 ? "miniprogram" : "link"));
                toast("已保存：" + cardTypeLabel());
            }
        })
        .setNegativeButton("取消", null)
        .show();
}

String cardTypeLabel() {
    String type = getString("card_type", "link");
    if ("text".equals(type)) return "纯文本";
    if ("miniprogram".equals(type)) return "小程序卡片";
    return "链接卡片";
}

// ── 工具函数 ────────────────────────────────────────────────────────────────
void setStatus(String value) {
    String previous = String.valueOf(gStatus.get());
    gStatus.set(value);
    if (!value.equals(previous)) log("状态: " + value);
}

String getStatus() {
    return String.valueOf(gStatus.get());
}

String shortError(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.length() == 0) message = error.getClass().getName();
    return truncate(message, 80);
}

String firstNonBlank(String a, String b, String fallback) {
    if (a != null && a.trim().length() > 0) return a.trim();
    if (b != null && b.trim().length() > 0) return b.trim();
    return fallback;
}

String emptyLabel(String value) {
    return value == null || value.trim().length() == 0 ? "未配置" : value.trim();
}

String maskSecret(String value) {
    if (value == null || value.length() == 0) return "未配置";
    if (value.length() <= 6) return "****";
    return value.substring(0, 3) + "****" + value.substring(value.length() - 3);
}

String xmlEscape(String value) {
    if (value == null) return "";
    String text = value;
    text = text.replace("&", "&amp;");
    text = text.replace("<", "&lt;");
    text = text.replace(">", "&gt;");
    text = text.replace("\"", "&quot;");
    return text;
}

String cdata(String value) {
    if (value == null) return "";
    return value.replace("]]>", "]]]]><![CDATA[>");
}

String jsonEscape(String value) {
    if (value == null) return "";
    String text = value;
    text = text.replace("\\", "\\\\");
    text = text.replace("\"", "\\\"");
    text = text.replace("\n", "\\n");
    text = text.replace("\r", "\\r");
    return text;
}

String truncate(String value, int max) {
    if (value == null) return "";
    if (value.length() <= max) return value;
    return value.substring(0, max) + "…";
}
