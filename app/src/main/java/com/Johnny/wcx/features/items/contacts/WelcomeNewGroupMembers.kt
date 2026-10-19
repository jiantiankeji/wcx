package com.Johnny.wcx.features.items.contacts

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.GroupSelectorScreen
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi
import h.Hchat.hooks.api.core.WeChatApis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

/**
 * 新人入群自动欢迎。
 *
 * 监测「选择群聊」中指定的群：只要成员列表新增了成员，就按配置的**消息列表依次发送**，
 * 并可在消息里 @ 新成员（接收方可收到「有人@我」提醒）。
 *
 * 支持的消息类型：文本、图片、链接卡片、名片卡片。
 *
 * 监测方式（与脚本子系统的成员变更事件同源，跨微信版本稳定，不依赖特征串）：
 * 订阅 `chatroom` 表的 memberlist 变更，取变更后的成员列表与上一次的成员列表做差集，
 * 差集即新入群成员。
 *
 * 防误触发：
 * - 首次见到某个群时只建立基线快照，不发送（避免冷启动把整群成员当成新人）；
 * - 单次差集超过 [maxBatch] 上限时视为全量同步，跳过并记录日志；
 * - 同一 (群, 成员) 在 [WELCOME_DEDUP_WINDOW_MS] 内只欢迎一次。
 *
 * @ 说明：@ 只会加在消息列表中的**第一条文本消息**上；若列表里没有文本消息，
 * 则本次不会 @ 新成员（只发图片/卡片），日志里会记录。
 */
@Feature(
    name = "新人入群自动欢迎",
    categories = ["联系人与群组"],
    description = "监测指定群聊的新成员，自动 @ 该成员并按配置依次发送文本/图片/链接卡片/名片卡片；可在设置里选择群聊、编辑消息列表"
)
object WelcomeNewGroupMembers : ClickableFeature() {

    private const val TAG = "WelcomeNewGroupMembers"

    /** 微信 @ 文本使用的分隔符（与 Hchat 发送 @ 消息的实现保持一致）。 */
    private const val AT_SEPARATOR = "\u2005"

    private const val GROUPS_KEY = "welcome_new_members_groups"
    private const val MESSAGES_KEY = "welcome_new_members_messages"

    /** 旧版（单条纯文本）配置键，保留以便平滑升级到消息列表。 */
    private const val LEGACY_MESSAGE_KEY = "welcome_new_members_message"

    private const val MENTION_KEY = "welcome_new_members_mention"
    private const val MAX_BATCH_KEY = "welcome_new_members_max_batch"

    /** 同一 (群, 成员) 的欢迎去重窗口。 */
    private const val WELCOME_DEDUP_WINDOW_MS = 5 * 60 * 1000L

    /** 多条消息之间的发送间隔，避免连续发送被微信节流。 */
    private const val SEND_INTERVAL_MS = 300L

    private const val MAX_SNAPSHOTS = 2048
    private const val MAX_RECENT_WELCOMES = 2048
    private const val DEFAULT_MAX_BATCH = 10

    /** 冷启动时 Hchat API 尚未就绪：等待「群聊变更监听」可用并重试订阅的参数（递增退避）。 */
    private const val SUBSCRIBE_MAX_ATTEMPTS = 40
    private const val SUBSCRIBE_MIN_DELAY_MS = 500L
    private const val SUBSCRIBE_MAX_DELAY_MS = 5000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── 消息模型 ──────────────────────────────────────────────────────────────

    enum class MessageKind(val description: String) {
        TEXT("文本"),
        IMAGE("图片"),
        LINK_CARD("链接卡片"),
        CONTACT_CARD("名片卡片"),
    }

    @Serializable
    data class WelcomeMessage(
        val kind: MessageKind = MessageKind.TEXT,
        /** 文本内容 */
        val text: String = "",
        /** 图片本地路径 */
        val imagePath: String = "",
        /** 链接卡片标题 */
        val cardTitle: String = "",
        /** 链接卡片描述 */
        val cardDescription: String = "",
        /** 链接卡片跳转地址 */
        val cardUrl: String = "",
        /** 链接卡片封面图本地路径（可选） */
        val cardThumbPath: String = "",
        /** 名片卡片：好友 wxId */
        val contactWxId: String = "",
    ) {
        fun isSendable(): Boolean = when (kind) {
            MessageKind.TEXT -> text.isNotBlank()
            MessageKind.IMAGE -> imagePath.isNotBlank()
            MessageKind.LINK_CARD -> cardUrl.isNotBlank() || cardTitle.isNotBlank()
            MessageKind.CONTACT_CARD -> contactWxId.isNotBlank()
        }

        fun summary(): String = when (kind) {
            MessageKind.TEXT -> text.lineSequence().firstOrNull().orEmpty().take(30)
            MessageKind.IMAGE -> imagePath.substringAfterLast('/')
            MessageKind.LINK_CARD -> cardTitle.ifBlank { cardUrl }.take(30)
            MessageKind.CONTACT_CARD -> contactWxId
        }
    }

    // ── 配置 ──────────────────────────────────────────────────────────────────

    /** 需要监测的群聊 wxId 集合（用户在设置里勾选）。 */
    private var selectedGroups by prefOption(GROUPS_KEY, emptySet<String>())

    /** 消息列表（JSON）。为空时回退到旧版单条文本配置。 */
    private var messagesJson by prefOption(MESSAGES_KEY, "")

    private var legacyMessage by prefOption(LEGACY_MESSAGE_KEY, "")

    /** 是否在欢迎消息里 @ 新成员。 */
    private var mentionNewMember by prefOption(MENTION_KEY, true)

    /** 单次最多欢迎几人；差集超过该值视为全量同步，跳过。 */
    private var maxBatch by prefOption(MAX_BATCH_KEY, DEFAULT_MAX_BATCH)

    /** 群 → 上一次看到的成员 wxId 集合。 */
    private val snapshots = ConcurrentHashMap<String, Set<String>>()

    /** "$groupWxId|$memberWxId" → 上次欢迎时间戳。 */
    private val recentWelcomes = ConcurrentHashMap<String, Long>()

    private val sendLock = Mutex()

    private var scope = newScope()

    private var subscription: WeChatChatroomChangeApi.Subscription? = null

    /**
     * 读取消息列表；新配置为空时用旧版单条文本兜底，保证升级后行为不变。
     */
    private fun loadMessages(): List<WelcomeMessage> {
        val raw = messagesJson
        if (raw.isNotBlank()) {
            val parsed = runCatching { json.decodeFromString<List<WelcomeMessage>>(raw) }
                .onFailure { WeLogger.w(TAG, "消息列表解析失败，回退到旧配置", it) }
                .getOrNull()
            if (parsed != null) return parsed
        }
        val legacy = legacyMessage.trim()
        return if (legacy.isEmpty()) {
            emptyList()
        } else {
            listOf(WelcomeMessage(kind = MessageKind.TEXT, text = legacy))
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        preloadSnapshots()
        ensureSubscribed()
    }

    /**
     * 订阅 chatroom 表变更。
     *
     * Hchat 的 API 由 [com.Johnny.wcx.features.hchat.HchatScriptBootstrap] 在**启动后异步**安装
     * （冷启动实测要 30 秒以上），而本功能的 startup 早于此。所以这里不能「拿不到 API 就放弃」，
     * 而要轮询等待就绪后再订阅——否则功能会静默失效，直到手动关开一次开关。
     */
    private fun ensureSubscribed() {
        if (subscription != null) return

        val changeApi = WeChatApis.chatroomChanges()
        if (changeApi != null && changeApi.isAvailable()) {
            subscribe(changeApi)
            return
        }
        scope.launch { retrySubscribe() }
    }

    private suspend fun retrySubscribe() {
        var delayMs = SUBSCRIBE_MIN_DELAY_MS
        repeat(SUBSCRIBE_MAX_ATTEMPTS) { attempt ->
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(SUBSCRIBE_MAX_DELAY_MS)
            if (subscription != null) return
            // 用户中途把功能关掉了，就没必要继续等。
            if (!isActive) return
            val changeApi = WeChatApis.chatroomChanges() ?: return@repeat
            if (!changeApi.isAvailable()) return@repeat
            WeLogger.i(TAG, "群聊变更监听已就绪（第 ${attempt + 1} 次探测），开始订阅")
            preloadSnapshots()
            subscribe(changeApi)
            return
        }
        WeLogger.w(
            TAG,
            "等待群聊变更监听就绪超时（${SUBSCRIBE_MAX_ATTEMPTS} 次），本次不生效；" +
                "可在设置里关开一次本功能重试"
        )
    }

    private fun subscribe(changeApi: WeChatChatroomChangeApi) {
        // install() 幂等；Hchat 引导流程也会安装，这里兜底一次，保证订阅能收到事件。
        runCatching { changeApi.install() }
            .onFailure { WeLogger.w(TAG, "安装群聊变更监听失败", it) }

        subscription = changeApi.subscribe { change -> onChatroomChanged(change) }
        WeLogger.i(
            TAG,
            "已订阅群聊成员变更，监测 ${selectedGroups.size} 个群聊，消息 ${loadMessages().size} 条"
        )
    }

    override fun onDisable() {
        runCatching { subscription?.unsubscribe() }
            .onFailure { WeLogger.w(TAG, "取消订阅失败", it) }
        subscription = null
        scope.cancel()
        snapshots.clear()
        recentWelcomes.clear()
    }

    // ── 成员变更处理 ──────────────────────────────────────────────────────────

    private fun onChatroomChanged(change: WeChatChatroomChangeApi.ChatroomChange) {
        // 只关心成员列表变化；roomdata（群昵称）变化等事件直接跳过。
        if (!change.mayMemberListChanged()) return

        val roomId = runCatching { change.chatroomId() }.getOrNull().orEmpty().trim()
        if (roomId.isEmpty()) return
        if (!roomId.isChatroomId()) return
        if (roomId !in selectedGroups) return

        // 数据库回调线程上只做筛选，读库/发送全部丢到 IO 协程，避免阻塞微信。
        val hinted = eventMemberIds(change)
        scope.launch { handleMemberDiff(roomId, hinted) }
    }

    /** [hinted] 是变更事件自带的成员列表，仅在重新读库失败时作为兜底。 */
    private suspend fun handleMemberDiff(roomId: String, hinted: Set<String>) {
        sendLock.withLock {
            if (roomId !in selectedGroups) return

            // 锁内重新读取权威成员列表：同一群短时间内可能有多次变更事件，
            // 协程调度顺序不保证，用事件快照会互相覆盖导致快照回退。
            val current = fetchMemberIds(roomId).ifEmpty { hinted }
            if (current.isEmpty()) return

            val previous = snapshots.put(roomId, current)
            trimSnapshots()
            // 首次见到该群（或基线为空）：只记录基线，绝不发送。
            if (previous.isNullOrEmpty()) {
                WeLogger.d(TAG, "建立成员基线 room=$roomId size=${current.size}")
                return
            }

            val joined = current.asSequence()
                .filter { it.isNotBlank() && it != WeApi.selfWxId }
                .filter { it !in previous }
                .toList()
            if (joined.isEmpty()) return

            if (joined.size > maxBatch.coerceAtLeast(1)) {
                WeLogger.w(
                    TAG,
                    "跳过疑似全量同步 room=$roomId 新增=${joined.size} 上限=$maxBatch" +
                        "（如确属多人同时入群，可在设置里调高上限）"
                )
                return
            }

            val fresh = joined.filter { markWelcomeOnce(roomId, it) }
            if (fresh.isEmpty()) {
                WeLogger.d(TAG, "新成员均已在去重窗口内欢迎过 room=$roomId")
                return
            }

            sendWelcome(roomId, fresh)
        }
    }

    // ── 发送 ──────────────────────────────────────────────────────────────────

    /** 按配置依次发送消息列表；@ 只加在第一条文本消息上。 */
    private suspend fun sendWelcome(roomId: String, memberIds: List<String>) {
        val messages = loadMessages().filter { it.isSendable() }
        if (messages.isEmpty()) {
            WeLogger.w(TAG, "没有可发送的欢迎消息，跳过 room=$roomId（请在设置里配置消息）")
            return
        }

        var mentioned = false
        for (message in messages) {
            val ok = when (message.kind) {
                MessageKind.TEXT -> {
                    if (!mentioned && mentionNewMember) {
                        mentioned = true
                        sendTextWithMention(roomId, memberIds, message.text.trim())
                    } else {
                        sendPlainText(roomId, message.text.trim())
                    }
                }
                MessageKind.IMAGE -> sendImageMessage(roomId, message.imagePath.trim())
                MessageKind.LINK_CARD -> sendLinkCardMessage(roomId, message)
                MessageKind.CONTACT_CARD -> sendContactCardMessage(roomId, message.contactWxId.trim())
            }
            WeLogger.i(
                TAG,
                "欢迎消息[${message.kind.description}] ${if (ok) "已发送" else "发送失败"} room=$roomId"
            )
            delay(SEND_INTERVAL_MS)
        }

        if (mentionNewMember && !mentioned) {
            WeLogger.w(TAG, "消息列表里没有文本消息，本次未能 @ 新成员 room=$roomId")
        }
        WeLogger.i(TAG, "已欢迎 room=$roomId members=${memberIds.joinToString(",")}")
    }

    private fun sendPlainText(roomId: String, text: String): Boolean {
        if (text.isEmpty()) return false
        val api = WeChatApis.messages() ?: return false
        return runCatching { api.sendText(roomId, text) }
            .onFailure { WeLogger.e(TAG, "发送文本失败 room=$roomId", it) }
            .getOrDefault(false)
    }

    private fun sendTextWithMention(roomId: String, memberIds: List<String>, text: String): Boolean {
        if (text.isEmpty()) return false
        val api = WeChatApis.messages() ?: return false
        return runCatching {
            val prefix = memberIds.joinToString("") { "@${atDisplayName(roomId, it)}$AT_SEPARATOR" }
            api.sendTextWithAtList(roomId, prefix + text, ArrayList(memberIds))
        }.onFailure { WeLogger.e(TAG, "发送 @ 文本失败 room=$roomId", it) }
            .getOrDefault(false)
    }

    private fun sendImageMessage(roomId: String, path: String): Boolean {
        if (path.isEmpty()) return false
        if (!File(path).exists()) {
            WeLogger.w(TAG, "图片不存在，跳过：$path")
            return false
        }
        val viaHchat = runCatching { WeChatApis.media()?.images()?.send(roomId, path) ?: false }
            .onFailure { WeLogger.e(TAG, "图片发送异常（Hchat）room=$roomId", it) }
            .getOrDefault(false)
        if (viaHchat) return true
        return runCatching { WeMessageApi.sendImage(roomId, path) }
            .onFailure { WeLogger.e(TAG, "图片发送异常（本地 API）room=$roomId", it) }
            .getOrDefault(false)
    }

    private fun sendLinkCardMessage(roomId: String, message: WelcomeMessage): Boolean {
        val xml = buildLinkCardXml(message)
        val thumb = readFileBytes(message.cardThumbPath)
        val viaLocal = runCatching { WeMessageApi.sendXmlAppMsg(roomId, xml, thumb) }
            .onFailure { WeLogger.e(TAG, "链接卡片发送异常（本地 API）room=$roomId", it) }
            .getOrDefault(false)
        if (viaLocal) return true
        return runCatching { WeChatApis.messages()?.sendXml(roomId, xml) ?: false }
            .onFailure { WeLogger.e(TAG, "链接卡片发送异常（Hchat）room=$roomId", it) }
            .getOrDefault(false)
    }

    private fun sendContactCardMessage(roomId: String, contactWxId: String): Boolean {
        if (contactWxId.isEmpty()) return false
        val viaHchat = runCatching { WeChatApis.messages()?.sendShareCard(roomId, contactWxId) ?: false }
            .onFailure { WeLogger.e(TAG, "名片发送异常（Hchat）room=$roomId", it) }
            .getOrDefault(false)
        if (viaHchat) return true
        return runCatching { WeMessageApi.sendShareCard(roomId, contactWxId) }
            .onFailure { WeLogger.e(TAG, "名片发送异常（本地 API）room=$roomId", it) }
            .getOrDefault(false)
    }

    /** 构造微信可解析的链接卡片 XML（appmsg type=5，外层用 `<msg>` 包裹）。 */
    private fun buildLinkCardXml(message: WelcomeMessage): String = buildString {
        append("<msg><appmsg appid=\"\" sdkver=\"0\">")
        append("<title>").append(xmlEscape(message.cardTitle)).append("</title>")
        append("<des>").append(xmlEscape(message.cardDescription)).append("</des>")
        append("<type>5</type>")
        append("<url>").append(xmlEscape(message.cardUrl)).append("</url>")
        append("</appmsg></msg>")
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun readFileBytes(path: String): ByteArray? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { File(trimmed).readBytes() }
            .onFailure { WeLogger.w(TAG, "读取封面图失败：$trimmed", it) }
            .getOrNull()
    }

    // ── 数据读取与工具 ────────────────────────────────────────────────────────

    /** 变更事件自带的成员列表（chatroom 表更新后的权威值），已规范化。 */
    private fun eventMemberIds(change: WeChatChatroomChangeApi.ChatroomChange): Set<String> =
        normalizeMembers(change.chatroom?.memberIds)

    /** 直接读 chatroom 表取成员列表。 */
    private fun fetchMemberIds(roomId: String): Set<String> {
        val ids = runCatching { WeChatApis.chatrooms()?.getMemberIds(roomId) }
            .onFailure { WeLogger.w(TAG, "读取群成员失败 room=$roomId", it) }
            .getOrNull()
            ?: return emptySet()
        return normalizeMembers(ids)
    }

    private fun normalizeMembers(ids: List<String>?): Set<String> =
        ids.orEmpty()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())

    /** @ 文本里使用的昵称：群昵称 → 群成员备注 → 好友备注/昵称 → wxId。 */
    private fun atDisplayName(roomId: String, memberId: String): String {
        val contacts = WeChatApis.contact().contacts()
        val name = runCatching { contacts?.getGroupMemberRoomDisplayName(roomId, memberId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != memberId }
            ?: runCatching { WeChatApis.chatrooms()?.getMemberDisplayName(roomId, memberId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != memberId }
            ?: runCatching { contacts?.getDisplayName(memberId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: memberId
        return name.replace('\n', ' ').replace('\r', ' ').trim().ifEmpty { memberId }
    }

    /** 功能开启时先给所有已知群建立基线，进一步降低「冷启动误报」。 */
    private fun preloadSnapshots() {
        runCatching {
            val chatrooms = WeChatApis.chatrooms() ?: return@runCatching
            for (room in chatrooms.getChatrooms()) {
                val roomId = room?.chatroomId?.trim().orEmpty()
                if (roomId.isEmpty()) continue
                val members = normalizeMembers(room.memberIds)
                if (members.isEmpty()) continue
                snapshots.putIfAbsent(roomId, members)
            }
            trimSnapshots()
        }.onFailure { WeLogger.w(TAG, "预加载群成员快照失败（忽略）", it) }
    }

    private fun markWelcomeOnce(roomId: String, memberId: String): Boolean {
        val now = System.currentTimeMillis()
        recentWelcomes.entries.removeAll { now - it.value > WELCOME_DEDUP_WINDOW_MS }
        val key = "$roomId|$memberId"
        val previous = recentWelcomes[key]
        if (previous != null && now - previous < WELCOME_DEDUP_WINDOW_MS) return false
        recentWelcomes[key] = now
        trimWelcomes()
        return true
    }

    private fun trimSnapshots() {
        while (snapshots.size > MAX_SNAPSHOTS) {
            val key = snapshots.keys.firstOrNull() ?: break
            snapshots.remove(key)
        }
    }

    private fun trimWelcomes() {
        if (recentWelcomes.size <= MAX_RECENT_WELCOMES) return
        val now = System.currentTimeMillis()
        recentWelcomes.entries.removeAll { now - it.value > WELCOME_DEDUP_WINDOW_MS }
        while (recentWelcomes.size > MAX_RECENT_WELCOMES) {
            val oldest = recentWelcomes.entries.minByOrNull { it.value } ?: break
            recentWelcomes.remove(oldest.key, oldest.value)
        }
    }

    private fun String.isChatroomId(): Boolean {
        val lower = lowercase(java.util.Locale.ROOT)
        return lower.endsWith("@chatroom") || lower.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── 媒体选择 ──────────────────────────────────────────────────────────────

    /**
     * 拉起系统图片选择器，把选中的图片拷贝到 moduleCache 并回调本地路径。
     * （与「定时发送消息」同一套做法：TransparentActivity + PickVisualMedia。）
     */
    private fun pickImage(onPicked: (String) -> Unit) {
        TransparentActivity.launch(HostInfo.application) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                copyUriToCache(uri)?.let(onPicked)
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun FragmentActivity.copyUriToCache(uri: Uri): String? {
        return try {
            val resolver = contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val safeName = displayName?.takeIf { it.isNotBlank() }
                ?: "picked_${System.currentTimeMillis()}"
            val destFile = KnownPaths.moduleCache.resolve("welcome_${System.currentTimeMillis()}_$safeName")
            resolver.openInputStream(uri)?.use { input ->
                Files.copy(input, destFile, StandardCopyOption.REPLACE_EXISTING)
            } ?: run {
                WeLogger.e(TAG, "copyUriToCache: openInputStream 返回 null，uri=$uri")
                return null
            }
            WeLogger.i(TAG, "已缓存所选图片：${destFile.absolutePathString()}")
            destFile.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "copyUriToCache 失败 uri=$uri", e)
            null
        }
    }

    // ── 设置界面 ──────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMention by remember { mutableStateOf(mentionNewMember) }
            var localMaxBatch by remember { mutableStateOf(maxBatch) }
            var localGroups by remember { mutableStateOf(selectedGroups) }
            var showGroupSelector by remember { mutableStateOf(false) }
            val messages = remember {
                mutableStateListOf<WelcomeMessage>().also { it.addAll(loadMessages()) }
            }

            if (showGroupSelector) {
                GroupSelectorScreen(
                    description = "选择需要自动欢迎新成员的群聊（可多选）",
                    initialSelected = localGroups,
                    onDismiss = { showGroupSelector = false },
                    onSave = { groups ->
                        localGroups = groups
                        showGroupSelector = false
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("新人入群自动欢迎") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                "勾选群聊后，这些群里每有一位新成员入群，就按下面的顺序依次发送消息，并 @ 他。" +
                                    "（@ 只会加在第一条文本消息上）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            messages.forEachIndexed { index, message ->
                                MessageEditor(
                                    index = index,
                                    message = message,
                                    onChange = { messages[index] = it },
                                    onDelete = { messages.removeAt(index) },
                                    onPickImage = { path ->
                                        messages[index] = messages[index].copy(imagePath = path)
                                    },
                                    onPickThumb = { path ->
                                        messages[index] = messages[index].copy(cardThumbPath = path)
                                    },
                                )
                            }

                            Button(
                                onClick = { messages.add(WelcomeMessage()) },
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text("添加消息")
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ListItem(
                                modifier = Modifier.clickable { localMention = !localMention },
                                trailingContent = {
                                    Switch(checked = localMention, onCheckedChange = null)
                                },
                                headlineContent = { Text("@ 新成员") },
                                supportingContent = {
                                    Text("开启后新成员会收到「有人@我」提醒；关闭则只发送消息")
                                }
                            )

                            OutlinedTextField(
                                value = localMaxBatch.toString(),
                                onValueChange = { v ->
                                    v.toIntOrNull()?.let { localMaxBatch = it.coerceIn(1, 200) }
                                },
                                label = { Text("单次最多欢迎人数") },
                                supportingText = {
                                    Text("一次同步里新增人数超过该值时会跳过（防止冷启动全量同步误发），默认 $DEFAULT_MAX_BATCH")
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ListItem(
                                modifier = Modifier.clickable { showGroupSelector = true },
                                headlineContent = { Text("选择群聊") },
                                supportingContent = {
                                    Text(
                                        if (localGroups.isEmpty()) {
                                            "尚未选择任何群聊（不会生效）"
                                        } else {
                                            "已选择 ${localGroups.size} 个群聊"
                                        }
                                    )
                                },
                                trailingContent = { Text("选择", fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val cleaned = messages
                                .map { it.copy(text = it.text.trim()) }
                                .filter { it.isSendable() }
                            messagesJson = json.encodeToString(cleaned)
                            mentionNewMember = localMention
                            maxBatch = localMaxBatch.coerceIn(1, 200)
                            selectedGroups = localGroups.toSet()
                            WeLogger.i(
                                TAG,
                                "设置已保存 groups=${selectedGroups.size} messages=${cleaned.size}" +
                                    " mention=$mentionNewMember maxBatch=$maxBatch"
                            )
                            // 启动时若因 Hchat 未就绪没能订阅上，保存设置后补订阅一次（无需重启微信）
                            if (isEnabled) ensureSubscribed()
                            showToast(
                                when {
                                    localGroups.isEmpty() -> "已保存（未选择群聊，功能不会生效）"
                                    cleaned.isEmpty() -> "已保存（消息为空，不会发送任何内容）"
                                    else -> "已保存，监测 ${localGroups.size} 个群聊 / ${cleaned.size} 条消息"
                                }
                            )
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessageEditor(
        index: Int,
        message: WelcomeMessage,
        onChange: (WelcomeMessage) -> Unit,
        onDelete: () -> Unit,
        onPickImage: (String) -> Unit,
        onPickThumb: (String) -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "消息 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Delete,
                        contentDescription = "删除消息"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MessageKind.values().forEach { kind ->
                    FilterChip(
                        selected = message.kind == kind,
                        onClick = { onChange(message.copy(kind = kind)) },
                        label = { Text(kind.description) }
                    )
                }
            }

            when (message.kind) {
                MessageKind.TEXT -> {
                    OutlinedTextField(
                        value = message.text,
                        onValueChange = { onChange(message.copy(text = it)) },
                        label = { Text("文本内容") },
                        supportingText = { Text("多行；@ 会加在第一条文本消息前") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }

                MessageKind.IMAGE -> {
                    PathField(
                        label = "图片路径",
                        value = message.imagePath,
                        onValueChange = { onChange(message.copy(imagePath = it)) },
                        onPick = onPickImage,
                    )
                }

                MessageKind.LINK_CARD -> {
                    OutlinedTextField(
                        value = message.cardTitle,
                        onValueChange = { onChange(message.copy(cardTitle = it)) },
                        label = { Text("卡片标题") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    OutlinedTextField(
                        value = message.cardDescription,
                        onValueChange = { onChange(message.copy(cardDescription = it)) },
                        label = { Text("卡片描述") },
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    OutlinedTextField(
                        value = message.cardUrl,
                        onValueChange = { onChange(message.copy(cardUrl = it)) },
                        label = { Text("跳转链接") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    PathField(
                        label = "封面图路径（可选）",
                        value = message.cardThumbPath,
                        onValueChange = { onChange(message.copy(cardThumbPath = it)) },
                        onPick = onPickThumb,
                    )
                }

                MessageKind.CONTACT_CARD -> {
                    OutlinedTextField(
                        value = message.contactWxId,
                        onValueChange = { onChange(message.copy(contactWxId = it)) },
                        label = { Text("好友 wxId") },
                        supportingText = { Text("要分享的名片对应的 wxId，例如 wxid_xxx") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    private fun PathField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        onPick: (String) -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
            TextButton(
                onClick = { pickImage(onPick) },
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text("从相册选择")
            }
        }
    }
}
