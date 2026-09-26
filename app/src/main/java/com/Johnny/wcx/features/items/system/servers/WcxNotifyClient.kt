package com.Johnny.wcx.features.items.system.servers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.GroupSelectorScreen
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 服务器通知转发客户端。
 *
 * 手机上的微信进程无法被公网服务器反向连接，因此这里由 wcx 主动向服务器建立 WebSocket 长连接：
 * 服务器（jzyh_backend）产生管理员通知后下发到本客户端，客户端再把通知文本转发到指定的微信群聊。
 *
 * 协议（纯文本 JSON 帧，服务端见 WcxNotifyWebSocket）：
 * - 连接地址：ws(s)://<host>[:port]/ws/wcx-notify?token=<共享令牌>
 * - 客户端上报：{"type":"hello","wxId":"..."}
 * - 客户端心跳：{"type":"ping"}（服务端回 {"type":"pong"}）
 * - 服务端下发：{"type":"notify","title":"...","content":"...","cardTitle":"...","cardDesc":"...",
 *   "link":"...","convId":"xxx@chatroom","id":1,"path":"..."}
 *   收到后先在手机本地弹出 Toast 提示，再转发到微信群里；转发目标以本地配置的群聊为准（可多选），
 *   本地未配置任何群时才回退到服务端下发的 convId。
 *   其中 cardTitle / cardDesc 是服务端过滤后的群文案（不含用户ID），群内展示以它为准。
 * - 开启「以小程序卡片转发」后走微信官方分享通道（WXMiniProgramObject）：由微信自行补齐小程序
 *   logo、名称、来源账号与包信息，避免手写 appmsg XML 时卡片退化成"未发布的小程序·体验版"。
 *   页面路径优先取服务端下发的 path，缺省时用本地配置的路径模板（{id} 占位通知 ID），
 *   点击卡片直接打开小程序详情页；卡片发送失败时自动回退为文本转发，避免通知丢失。
 */
@Feature(
    name = "服务器通知转发",
    categories = ["系统与隐私"],
    description = "主动连接公网服务器接收通知，并转发到指定的微信群聊"
)
object WcxNotifyClient : ClickableFeature() {

    private const val TAG = "WcxNotifyClient"

    private const val NOTIFY_PATH = "/ws/wcx-notify"

    private const val PING_FRAME = """{"type":"ping"}"""

    /** 心跳间隔，同时用于保活 NAT 映射。 */
    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private const val MAX_RECONNECT_DELAY_MS = 60_000L

    /** 连通检测整体超时（含握手与等待 pong）。 */
    private const val CONNECT_CHECK_TIMEOUT_MS = 10_000L

    /** 连接被服务端关闭后，等待关闭原因的最长时间。 */
    private const val CLOSE_REASON_WAIT_MS = 2_000L

    /** 卡片详情页路径中的通知 ID 占位符。 */
    private const val CARD_PAGE_ID_PLACEHOLDER = "{id}"

    /** 微信 WXMediaMessage.thumbData 上限 32KB，超出会导致分享被微信拒绝。 */
    private const val CARD_THUMB_MAX_BYTES = 32 * 1024

    /** 卡片封面最大边长（卡片按小图展示，无需原图分辨率）。 */
    private const val CARD_THUMB_MAX_EDGE = 300

    /** 卡片封面下载超时。 */
    private const val CARD_THUMB_TIMEOUT_MS = 5_000

    private var serverUrl by prefOption("wcx_notify_server_url", "")

    private var notifyToken by prefOption("wcx_notify_token", "")

    /** 通知转发目标群（群 ID 以 @chatroom 结尾），可多选。 */
    private var notifyGroupIds by prefOption("wcx_notify_group_ids", emptySet<String>())

    /** 是否以小程序卡片（appmsg type=33）形式转发通知，关闭时转发纯文本。 */
    private var notifyCardEnabled by prefOption("wcx_notify_card_enabled", false)

    /** 卡片所属小程序原始 ID（username，形如 gh_xxx@app）。 */
    private var notifyCardUsername by prefOption("wcx_notify_card_username", "")

    /** 详情页路径模板，{id} 会被替换为通知 ID，如 pages/post/post-detail?id={id}。 */
    private var notifyCardPagePath by prefOption("wcx_notify_card_page_path", "")

    /** 卡片缩略图 URL，留空则使用小程序默认封面。 */
    private var notifyCardThumbUrl by prefOption("wcx_notify_card_thumb_url", "")

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        HttpClient(CIO) {
            install(WebSockets)
        }
    }

    private var connectJob: Job? = null

    // -------------------------------------------------------------------------
    // 协议模型
    // -------------------------------------------------------------------------

    @Serializable
    private data class NotifyMessage(
        val type: String,
        val title: String? = null,
        val content: String? = null,
        /** 群卡片标题：服务端过滤后的文案，缺省时回退 title。 */
        val cardTitle: String? = null,
        /** 群卡片描述：服务端过滤后的文案（不含用户ID），缺省时回退 content。 */
        val cardDesc: String? = null,
        val link: String? = null,
        val convId: String? = null,
        /** 通知 ID，用于填充卡片详情页路径中的 {id} 占位符。 */
        val id: Long? = null,
        /** 服务端直接指定的卡片页面路径（含查询参数），优先于本地路径模板。 */
        val path: String? = null,
    )

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    override fun onEnable() {
        start()
    }

    override fun onDisable() {
        connectJob?.cancel()
        connectJob = null
        _status.value = "已停止"
    }

    /** 保存配置后重建连接。 */
    private fun restart() {
        connectJob?.cancel()
        connectJob = null
        start()
    }

    private fun start() {
        if (connectJob?.isActive == true) return
        if (serverUrl.isBlank()) {
            _status.value = "未配置服务器地址"
            return
        }
        if (notifyToken.isBlank()) {
            _status.value = "未配置共享令牌"
            return
        }
        connectJob = scope.launch { runConnectionLoop() }
    }

    // -------------------------------------------------------------------------
    // 连接与重连
    // -------------------------------------------------------------------------

    private suspend fun runConnectionLoop() {
        var retryDelay = INITIAL_RECONNECT_DELAY_MS
        while (currentCoroutineContext().isActive) {
            try {
                httpClient.webSocket(urlString = buildWsUrl()) {
                    _status.value = "已连接"
                    retryDelay = INITIAL_RECONNECT_DELAY_MS
                    WeLogger.i(TAG, "已连接服务器 $serverUrl")

                    send(Frame.Text(buildHelloJson()))

                    val heartbeat = launch {
                        while (isActive) {
                            delay(HEARTBEAT_INTERVAL_MS)
                            // 心跳发送失败说明链路已不可用，停止心跳，交由外层接收循环结束并重连
                            val sent = runCatching { send(Frame.Text(PING_FRAME)) }.isSuccess
                            if (!sent) break
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleText(frame.readText())
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
                _status.value = "连接已断开"
                WeLogger.w(TAG, "与服务器的连接已断开")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "连接失败：${e.message ?: "未知错误"}"
                WeLogger.w(TAG, "连接服务器失败: ${e.message}", e)
            }

            if (!currentCoroutineContext().isActive) break
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }

    private fun buildWsUrl(baseUrl: String = serverUrl, token: String = notifyToken): String {
        var base = baseUrl.trim()
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        base = base.trimEnd('/')
        if (!base.endsWith(NOTIFY_PATH)) base += NOTIFY_PATH
        return "$base?token=${URLEncoder.encode(token, "UTF-8")}"
    }

    private fun buildHelloJson(): String {
        // 首次连接时微信账号信息可能尚未就绪，取不到就上报空值，服务端只用于日志展示。
        val wxId = runCatching { WeApi.selfWxId }.getOrDefault("")
        return """{"type":"hello","wxId":"$wxId"}"""
    }

    // -------------------------------------------------------------------------
    // 连通检测
    // -------------------------------------------------------------------------

    /** 单次探测的结果。 */
    private sealed interface ProbeResult {
        /** 握手成功且在超时内收到了 pong。 */
        data object Pong : ProbeResult

        /** 连接被服务端关闭（例如令牌校验失败、服务端未启用该通道）。 */
        data class Closed(val reason: String?) : ProbeResult
    }

    /**
     * 对「待保存」的服务器地址与共享令牌做一次独立的连通检测：临时建立一条 WebSocket，
     * 发送 hello + ping，并在超时时间内等待服务端回 pong。
     *
     * 检测不会读写偏好项，也不影响正在运行的长连接，供配置对话框在保存前自检；
     * 返回人类可读的结果文本，可直接展示在界面上。
     */
    suspend fun checkConnectivity(
        rawServerUrl: String,
        rawToken: String,
        timeoutMs: Long = CONNECT_CHECK_TIMEOUT_MS,
    ): String {
        val baseUrl = rawServerUrl.trim()
        val token = rawToken.trim()
        if (baseUrl.isBlank()) return "未填写服务器地址"
        if (token.isBlank()) return "未填写共享令牌"

        val startedAt = System.currentTimeMillis()
        return try {
            val elapsed = { System.currentTimeMillis() - startedAt }
            when (val result = withTimeout(timeoutMs) { probeServer(buildWsUrl(baseUrl, token)) }) {
                ProbeResult.Pong -> "连通正常（${elapsed()}ms）"
                is ProbeResult.Closed -> result.reason?.takeIf { it.isNotBlank() }
                    ?.let { "已连上服务器，但被拒绝：$it" }
                    ?: "已连上服务器，但未收到心跳响应"
            }
        } catch (e: TimeoutCancellationException) {
            "检测超时（${timeoutMs / 1000} 秒内无响应）"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "无法连接：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun probeServer(wsUrl: String): ProbeResult {
        var pongReceived = false
        var closeReasonText: String? = null
        httpClient.webSocket(urlString = wsUrl) {
            send(Frame.Text(buildHelloJson()))
            send(Frame.Text(PING_FRAME))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                if (parseType(frame.readText()) == "pong") {
                    pongReceived = true
                    break
                }
            }
            if (!pongReceived) {
                // 服务端拒绝连接时会带上原因（令牌校验失败 / 服务端未启用该通道）
                closeReasonText = withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { closeReason.await() }
                    ?.message
            }
        }
        return if (pongReceived) ProbeResult.Pong else ProbeResult.Closed(closeReasonText)
    }

    private fun parseType(text: String): String? =
        runCatching { json.decodeFromString<NotifyMessage>(text).type }.getOrNull()

    // -------------------------------------------------------------------------
    // 消息处理
    // -------------------------------------------------------------------------

    private suspend fun handleText(text: String) {
        val message = runCatching { json.decodeFromString<NotifyMessage>(text) }.getOrNull()
        if (message == null) {
            WeLogger.w(TAG, "无法解析服务端消息: $text")
            return
        }
        when (message.type) {
            "notify" -> {
                // 先弹提示再转发：转发包含阻塞的消息发送，可能耗时较久
                showToastSuspend(buildToastText(message))
                forwardToGroups(message)
            }

            "pong" -> Unit
            else -> WeLogger.d(TAG, "忽略未知消息类型: ${message.type}")
        }
    }

    private suspend fun forwardToGroups(message: NotifyMessage) {
        val configured = notifyGroupIds.filter { it.isNotBlank() }
        val targets = configured.ifEmpty {
            listOfNotNull(message.convId?.trim()?.takeIf { it.isNotBlank() })
        }
        if (targets.isEmpty()) {
            WeLogger.w(TAG, "收到通知但未配置目标群，已忽略")
            _status.value = "已连接（通知缺少目标群）"
            return
        }

        val content = buildContent(message)
        val card = buildCard(message)
        var successCount = 0
        var lastError: String? = null
        for (convId in targets) {
            // 卡片被微信拒绝（配置不合法、分享失败等）时回退为文本，避免通知丢失
            val cardResult = card?.let { sendCard(convId, it) }
            val result = if (cardResult is WeChatService.Result.Error) {
                WeLogger.w(TAG, "卡片转发到 $convId 失败，回退为文本: ${cardResult.message}")
                WeChatService.sendMessage("text", convId, content)
            } else {
                cardResult ?: WeChatService.sendMessage("text", convId, content)
            }

            when (result) {
                is WeChatService.Result.Success -> {
                    successCount++
                    WeLogger.i(TAG, "通知已转发到 $convId")
                }

                is WeChatService.Result.Error -> {
                    lastError = result.message
                    WeLogger.e(TAG, "通知转发到 $convId 失败: ${result.message}")
                }
            }
        }

        _status.value = when {
            successCount == targets.size -> "已连接（最近转发：${targets.size} 个群）"
            successCount > 0 -> "已连接（${successCount}/${targets.size} 个群转发成功）"
            else -> "已连接（转发失败：${lastError ?: "未知错误"}）"
        }
    }

    /**
     * 群内转发的文本内容。
     *
     * 优先使用服务端下发的 cardTitle / cardDesc（已过滤用户ID），缺省时才回退通知标题与内容，
     * 保证群聊里不会出现管理端才需要的用户ID等定位信息。
     */
    private fun buildContent(message: NotifyMessage): String = buildString {
        append("【").append(cardTitleOf(message)).append("】\n")
        cardDescOf(message)?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        message.link?.takeIf { it.isNotBlank() }?.let { append("详情：").append(it) }
    }.trim()

    private fun cardTitleOf(message: NotifyMessage): String =
        message.cardTitle?.takeIf { it.isNotBlank() }
            ?: message.title?.takeIf { it.isNotBlank() }
            ?: "系统通知"

    private fun cardDescOf(message: NotifyMessage): String? =
        message.cardDesc?.takeIf { it.isNotBlank() } ?: message.content

    /** 收到通知时本地弹出的提示文本（Toast 单行显示，过长的内容会截断）。 */
    private fun buildToastText(message: NotifyMessage): String {
        val title = message.title?.takeIf { it.isNotBlank() } ?: "服务器通知"
        val body = message.content?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
        return "【$title】${body.orEmpty()}".take(100)
    }

    // -------------------------------------------------------------------------
    // 小程序卡片转发
    // -------------------------------------------------------------------------

    /** 一次小程序分享所需的入参。 */
    private data class CardPayload(
        val title: String,
        val description: String,
        val username: String,
        val path: String,
        val thumbBase64: String?,
    )

    /**
     * 准备转发用的小程序卡片参数。
     *
     * 未开启卡片转发或配置不完整（缺少小程序原始 ID / 页面路径）时返回 null，由调用方回退为文本转发。
     */
    private suspend fun buildCard(message: NotifyMessage): CardPayload? {
        if (!notifyCardEnabled) return null

        val username = notifyCardUsername.trim()
        val pagePath = resolveCardPagePath(message)
        if (username.isEmpty() || pagePath.isEmpty()) {
            WeLogger.w(TAG, "已开启卡片转发但小程序信息配置不完整，回退为文本转发")
            return null
        }

        return CardPayload(
            title = cardTitleOf(message),
            description = cardDescOf(message).orEmpty(),
            username = username,
            path = pagePath,
            thumbBase64 = loadCardThumbBase64(),
        )
    }

    /**
     * 通过微信官方分享通道发送小程序卡片。
     *
     * 相比手写 appmsg XML，官方通道由微信补齐 weappiconurl / shareId / pkginfo / 来源账号等字段，
     * 卡片才能带上小程序 logo、名称与"小程序"标签；appId 传空串（小程序身份由 username 决定）。
     */
    private fun sendCard(convId: String, card: CardPayload): WeChatService.Result<Unit> =
        WeChatService.shareMiniProgram(
            toUser = convId,
            title = card.title,
            description = card.description,
            username = card.username,
            path = card.path,
            thumbDataBase64 = card.thumbBase64,
            appId = "",
        )

    /**
     * 下载并压缩本地配置的卡片封面。
     *
     * 微信对 thumbData 有 32KB 的硬限制，超限会让整条卡片被拒绝发送，因此先按边长缩小再逐级降质；
     * 仍压不进限制内（或未配置/下载失败）时返回 null，由微信使用小程序默认封面。
     */
    private fun loadCardThumbBase64(): String? {
        val url = notifyCardThumbUrl.trim()
        if (url.isEmpty()) return null
        return try {
            val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = CARD_THUMB_TIMEOUT_MS
                readTimeout = CARD_THUMB_TIMEOUT_MS
                try {
                    inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            }
            compressCardThumb(bytes)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } catch (e: Exception) {
            WeLogger.w(TAG, "下载卡片封面失败，改用小程序默认封面: ${e.message}")
            null
        }
    }

    /** 将封面压缩到微信允许的体积；无法压到限制内时返回 null。 */
    private fun compressCardThumb(bytes: ByteArray): ByteArray? {
        if (bytes.size <= CARD_THUMB_MAX_BYTES) return bytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CARD_THUMB_MAX_EDGE
            && bounds.outHeight / (sample * 2) >= CARD_THUMB_MAX_EDGE
        ) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        return try {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longEdge > CARD_THUMB_MAX_EDGE) {
                val ratio = CARD_THUMB_MAX_EDGE.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }

            var quality = 85
            var compressed: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                compressed = stream.toByteArray()
                quality -= 15
            } while (compressed.size > CARD_THUMB_MAX_BYTES && quality > 20)

            if (compressed.size > CARD_THUMB_MAX_BYTES) {
                WeLogger.w(TAG, "卡片封面压缩后仍超过 ${CARD_THUMB_MAX_BYTES / 1024}KB，改用小程序默认封面")
                null
            } else {
                compressed
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 卡片页面路径：优先用服务端下发的 path，其次用本地模板并替换 {id} 占位符。 */
    private fun resolveCardPagePath(message: NotifyMessage): String {
        message.path?.takeIf { it.isNotBlank() }?.let { return it.trim() }

        val template = notifyCardPagePath.trim()
        if (!template.contains(CARD_PAGE_ID_PLACEHOLDER)) return template

        val id = message.id?.toString()
        // 服务端未下发通知 ID 时去掉占位参数，避免生成 ?id= 这类非法路径
        if (id.isNullOrBlank()) {
            return template
                .replace("?id=$CARD_PAGE_ID_PLACEHOLDER", "")
                .replace("&id=$CARD_PAGE_ID_PLACEHOLDER", "")
        }
        return template.replace(CARD_PAGE_ID_PLACEHOLDER, id)
    }

    // -------------------------------------------------------------------------
    // 配置界面
    // -------------------------------------------------------------------------

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var serverUrlInput by remember { mutableStateOf(serverUrl) }
            var tokenInput by remember { mutableStateOf(notifyToken) }
            var localGroupIds by remember { mutableStateOf(notifyGroupIds) }
            var showGroupSelector by remember { mutableStateOf(false) }
            var checking by remember { mutableStateOf(false) }
            var checkResult by remember { mutableStateOf<String?>(null) }
            var cardEnabled by remember { mutableStateOf(notifyCardEnabled) }
            var cardUsername by remember { mutableStateOf(notifyCardUsername) }
            var cardPagePath by remember { mutableStateOf(notifyCardPagePath) }
            var cardThumbUrl by remember { mutableStateOf(notifyCardThumbUrl) }
            val currentStatus by status.collectAsState()
            val dialogScope = rememberCoroutineScope()

            if (showGroupSelector) {
                GroupSelectorScreen(
                    description = "选择需要接收服务器通知的群聊，可多选",
                    initialSelected = localGroupIds,
                    onDismiss = { showGroupSelector = false },
                    onSave = { groups ->
                        localGroupIds = groups
                        showToast("已选择 ${groups.size} 个群聊")
                        showGroupSelector = false
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("服务器通知转发") },
                    text = {
                        DefaultColumn(scrollable = true) {
                            Text("连接状态：$currentStatus")
                            TextField(
                                value = serverUrlInput,
                                onValueChange = { serverUrlInput = it },
                                label = { Text("服务器地址，如 wss://jzxyq.vip") },
                            )
                            TextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("共享令牌") },
                            )
                            ListItem(
                                headlineContent = { Text("服务器连通检测") },
                                supportingContent = {
                                    val message = if (checking) {
                                        "正在检测，请稍候…"
                                    } else {
                                        checkResult ?: "保存前可先检测地址与令牌是否可用"
                                    }
                                    Text(message)
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            checking = true
                                            checkResult = null
                                            dialogScope.launch {
                                                val result = checkConnectivity(serverUrlInput, tokenInput)
                                                checkResult = result
                                                checking = false
                                            }
                                        },
                                        enabled = !checking,
                                    ) { Text(if (checking) "检测中…" else "检测") }
                                }
                            )
                            ListItem(
                                modifier = Modifier.clickable { showGroupSelector = true },
                                headlineContent = { Text("选择群聊") },
                                supportingContent = { Text("当前已选 ${localGroupIds.size} 个群聊") }
                            )
                            ListItem(
                                headlineContent = { Text("以小程序卡片转发") },
                                supportingContent = {
                                    Text(
                                        if (cardEnabled) "点击卡片直接打开小程序详情页"
                                        else "关闭时转发纯文本消息"
                                    )
                                },
                                trailingContent = {
                                    Switch(checked = cardEnabled, onCheckedChange = { cardEnabled = it })
                                }
                            )
                            if (cardEnabled) {
                                TextField(
                                    value = cardUsername,
                                    onValueChange = { cardUsername = it },
                                    label = { Text("小程序原始 ID，如 gh_xxx@app") },
                                )
                                TextField(
                                    value = cardPagePath,
                                    onValueChange = { cardPagePath = it },
                                    label = { Text("详情页路径，{id} 占位通知 ID") },
                                )
                                TextField(
                                    value = cardThumbUrl,
                                    onValueChange = { cardThumbUrl = it },
                                    label = { Text("卡片封面 URL（可空）") },
                                )
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = {
                            serverUrl = serverUrlInput.trim()
                            notifyToken = tokenInput.trim()
                            notifyGroupIds = localGroupIds
                            notifyCardEnabled = cardEnabled
                            notifyCardUsername = cardUsername.trim()
                            notifyCardPagePath = cardPagePath.trim()
                            notifyCardThumbUrl = cardThumbUrl.trim()
                            restart()
                            onDismiss()
                        }) { Text("保存并重连") }
                    },
                )
            }
        }
    }
}
