package h.Hchat.dexkit

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import com.Johnny.wcx.utils.HostInfo
import h.Hchat.preferences.HchatStorage
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DexKit 特征包（远程可热更新）。
 *
 * 背景：WCX 的 hook 点定位锚点此前硬编码在 [DexFinder] 里，微信更新后特征失效只能重发 APK。
 *
 * 目标：把「hook 点定位锚点」抽成可在线上更新的数据（JSON）。微信更新导致内置特征失效时，
 * 只需更新特征包，模块启动时自动拉取生效，**无需重装 APK**。
 *
 * 载入与降级策略：
 *   1. 先用本地缓存（保证离线可用）；
 *   2. 后台异步从多源拉取覆盖；
 *   3. 按微信版本查表：精确版本 → 主次版本（如 "8.0"）→ "default" 段；
 *   4. 全部缺失时返回空，调用方继续使用内置特征（不影响功能）。
 *
 * 拉取来源（按序尝试，任一成功即止；全部失败则沿用缓存）：
 *   GitHub raw 主源 + jsDelivr CDN 备源。
 *
 * 该对象所有对外方法均线程安全；网络操作只在内部 HandlerThread 上执行，绝不阻塞主线程。
 */
object DexFeaturePack {

    private const val TAG = "DexFeaturePack"
    private const val PREFS_NAME = "Hchat_dex_feature_pack"
    private const val KEY_JSON = "pack_json"
    private const val KEY_FETCH_AT = "pack_fetched_at"
    private const val KEY_SOURCE = "pack_source"
    private const val SUPPORTED_SCHEMA = 1
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000
    private const val MAX_BYTES = 512 * 1024
    private const val SECTION_DEFAULT = "default"

    /** 远程特征包源（按顺序尝试）。 */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/Johnny520/wcx/feature-pack/pack.json",
        "https://cdn.jsdelivr.net/gh/Johnny520/wcx@feature-pack/pack.json",
    )

    @Volatile
    private var payload: JSONObject? = null

    @Volatile
    private var lastStatus: String = "未初始化"

    @Volatile
    private var lastFetchAt: String = ""

    @Volatile
    private var lastSource: String = ""

    private var prefs: SharedPreferences? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var initialized = false

    /**
     * 初始化：读取本地缓存并生效，然后后台异步刷新。
     * 重复调用安全（幂等）。
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ctx = context.applicationContext ?: context
        prefs = runCatching { HchatStorage.preferences(ctx, PREFS_NAME) }.getOrNull()
        val cached = runCatching { prefs?.getString(KEY_JSON, null) }.getOrNull()
        if (!cached.isNullOrBlank()) {
            applyPayload(
                cached,
                prefs?.getString(KEY_SOURCE, "").orEmpty(),
                prefs?.getString(KEY_FETCH_AT, "").orEmpty(),
            )
        } else {
            lastStatus = "无本地缓存（等待远程更新）"
        }
        ensureHandler()
        handler?.post { fetchAndApply() }
    }

    /** 手动触发一次远程刷新（诊断页/一键修复用）。 */
    fun refresh() {
        ensureHandler()
        handler?.post { fetchAndApply() }
    }

    private fun ensureHandler() {
        if (handler == null) {
            val t = runCatching { HandlerThread("WCX-DexFeaturePack").apply { start() } }.getOrNull()
            thread = t
            handler = t?.looper?.let { Handler(it) }
        }
    }

    private fun fetchAndApply() {
        var applied = false
        for (url in SOURCES) {
            val text = httpGet(url) ?: continue
            if (applyPayload(text, url, nowIso())) {
                applied = true
                break
            }
        }
        if (!applied) {
            lastStatus = if (payload != null) "远程更新失败，沿用本地缓存" else "远程更新失败（无可用特征包）"
            WeLogger.i(TAG, lastStatus)
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WCX-DexFeaturePack")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                WeLogger.i(TAG, "拉取失败 HTTP $code: $url")
                return null
            }
            input = conn.inputStream
            readLimited(input, MAX_BYTES)
        } catch (t: Throwable) {
            WeLogger.i(TAG, "拉取异常: " + (t.message ?: t.javaClass.simpleName))
            null
        } finally {
            runCatching { input?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** 解析并生效一个特征包。返回是否成功。 */
    @Synchronized
    private fun applyPayload(text: String, source: String, fetchedAt: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null) {
            lastStatus = "特征包解析失败（非法 JSON）"
            return false
        }
        val schema = root.optInt("schema", 0)
        if (schema != SUPPORTED_SCHEMA) {
            lastStatus = "特征包 schema 不兼容（$schema != $SUPPORTED_SCHEMA）"
            return false
        }
        val packs = root.optJSONObject("packs")
        if (packs == null) {
            lastStatus = "特征包缺少 packs 段"
            return false
        }
        payload = packs
        lastSource = source
        lastFetchAt = fetchedAt
        val v = wechatVersion()
        val section = resolveSection(packs, v)
        val count = section?.length() ?: 0
        lastStatus = "已生效：微信 $v 特征 $count 条（来源 ${shortSource(source)}）"
        runCatching {
            prefs?.edit()
                ?.putString(KEY_JSON, text)
                ?.putString(KEY_SOURCE, source)
                ?.putString(KEY_FETCH_AT, fetchedAt)
                ?.apply()
        }
        WeLogger.i(TAG, lastStatus)
        return true
    }

    private fun resolveSection(packs: JSONObject, version: String): JSONObject? {
        if (version.isNotBlank()) {
            packs.optJSONObject(version)?.let { return it }
            // 允许 "8.0.79" 命中 "8.0" 段
            val major = version.split('.').take(2).joinToString(".")
            if (major.isNotBlank() && major != version) {
                packs.optJSONObject(major)?.let { return it }
            }
        }
        return packs.optJSONObject(SECTION_DEFAULT)
    }

    fun classAnchors(apiKey: String): List<Array<String>> = anchors(apiKey, "classAnchors")

    fun methodAnchors(apiKey: String): List<Array<String>> = anchors(apiKey, "methodAnchors")

    private fun anchors(apiKey: String, field: String): List<Array<String>> {
        val packs = payload ?: return emptyList()
        val section = resolveSection(packs, wechatVersion()) ?: return emptyList()
        val api = section.optJSONObject(apiKey) ?: return emptyList()
        val arr = api.optJSONArray(field) ?: return emptyList()
        val out = ArrayList<Array<String>>()
        for (i in 0 until arr.length()) {
            val inner = arr.optJSONArray(i) ?: continue
            val list = ArrayList<String>()
            for (j in 0 until inner.length()) {
                val s = inner.optString(j, "")
                if (s.isNotBlank()) list.add(s)
            }
            if (list.isNotEmpty()) out.add(list.toTypedArray())
        }
        return out
    }

    /** 特征包为某 API 提供的构造函数签名列表（Dex 描述符，如 "(Ljava/lang/String;...)V"）。 */
    fun ctorTypes(apiKey: String): List<String> {
        val packs = payload ?: return emptyList()
        val section = resolveSection(packs, wechatVersion()) ?: return emptyList()
        val api = section.optJSONObject(apiKey) ?: return emptyList()
        val arr = api.optJSONArray("ctorTypes") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** 当前微信版本下已生效的 API 特征条数（诊断用）。 */
    fun effectiveApiCount(): Int {
        val packs = payload ?: return 0
        val section = resolveSection(packs, wechatVersion()) ?: return 0
        return section.length()
    }

    /** 诊断展示：当前状态摘要。 */
    fun statusSummary(): String {
        val src = if (lastSource.isBlank()) "无" else shortSource(lastSource)
        return "$lastStatus（源=$src，拉取=$lastFetchAt）"
    }

    private fun wechatVersion(): String = runCatching { HostInfo.versionName }.getOrDefault("")

    private fun nowIso(): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    }.getOrDefault("")

    private fun shortSource(url: String): String = when {
        url.contains("jsdelivr") -> "jsDelivr"
        url.contains("raw.githubusercontent") -> "GitHub"
        url.startsWith("http") -> "远程"
        else -> url
    }
}
