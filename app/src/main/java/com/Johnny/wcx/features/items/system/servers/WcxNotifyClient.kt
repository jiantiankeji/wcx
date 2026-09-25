package com.Johnny.wcx.features.items.system.servers

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * - 服务端下发：{"type":"notify","title":"...","content":"...","link":"...","convId":"xxx@chatroom"}
 *   convId 为空时回退到本地配置的默认群。
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

    private var serverUrl by prefOption("wcx_notify_server_url", "")

    private var notifyToken by prefOption("wcx_notify_token", "")

    /** 服务端未下发 convId 时使用的默认群（群 ID 以 @chatroom 结尾）。 */
    private var defaultGroupId by prefOption("wcx_notify_default_group", "")

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
        val link: String? = null,
        val convId: String? = null,
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

    private fun buildWsUrl(): String {
        var base = serverUrl.trim()
        base = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        base = base.trimEnd('/')
        if (!base.endsWith(NOTIFY_PATH)) base += NOTIFY_PATH
        return "$base?token=${URLEncoder.encode(notifyToken, "UTF-8")}"
    }

    private fun buildHelloJson(): String {
        // 首次连接时微信账号信息可能尚未就绪，取不到就上报空值，服务端只用于日志展示。
        val wxId = runCatching { WeApi.selfWxId }.getOrDefault("")
        return """{"type":"hello","wxId":"$wxId"}"""
    }

    // -------------------------------------------------------------------------
    // 消息处理
    // -------------------------------------------------------------------------

    private fun handleText(text: String) {
        val message = runCatching { json.decodeFromString<NotifyMessage>(text) }.getOrNull()
        if (message == null) {
            WeLogger.w(TAG, "无法解析服务端消息: $text")
            return
        }
        when (message.type) {
            "notify" -> forwardToGroup(message)
            "pong" -> Unit
            else -> WeLogger.d(TAG, "忽略未知消息类型: ${message.type}")
        }
    }

    private fun forwardToGroup(message: NotifyMessage) {
        val convId = message.convId?.trim()?.takeIf { it.isNotBlank() }
            ?: defaultGroupId.takeIf { it.isNotBlank() }
        if (convId == null) {
            WeLogger.w(TAG, "收到通知但未指定目标群，已忽略")
            _status.value = "已连接（通知缺少目标群）"
            return
        }

        val content = buildContent(message)
        when (val result = WeChatService.sendMessage("text", convId, content)) {
            is WeChatService.Result.Success -> {
                WeLogger.i(TAG, "通知已转发到 $convId")
                _status.value = "已连接（最近转发：$convId）"
            }

            is WeChatService.Result.Error -> {
                WeLogger.e(TAG, "通知转发失败: ${result.message}")
                _status.value = "已连接（转发失败：${result.message}）"
            }
        }
    }

    private fun buildContent(message: NotifyMessage): String = buildString {
        append("【").append(message.title?.takeIf { it.isNotBlank() } ?: "系统通知").append("】\n")
        message.content?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        message.link?.takeIf { it.isNotBlank() }?.let { append("详情：").append(it) }
    }.trim()

    // -------------------------------------------------------------------------
    // 配置界面
    // -------------------------------------------------------------------------

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var serverUrlInput by remember { mutableStateOf(serverUrl) }
            var tokenInput by remember { mutableStateOf(notifyToken) }
            var groupInput by remember { mutableStateOf(defaultGroupId) }
            val currentStatus by status.collectAsState()

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
                        TextField(
                            value = groupInput,
                            onValueChange = { groupInput = it },
                            label = { Text("默认群 ID（xxx@chatroom）") },
                        )
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        serverUrl = serverUrlInput.trim()
                        notifyToken = tokenInput.trim()
                        defaultGroupId = groupInput.trim()
                        restart()
                        onDismiss()
                    }) { Text("保存并重连") }
                },
            )
        }
    }
}
