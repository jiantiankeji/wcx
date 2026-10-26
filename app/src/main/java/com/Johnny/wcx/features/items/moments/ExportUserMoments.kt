package com.Johnny.wcx.features.items.moments

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton as UiTextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.fs.KnownPaths
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatSnsPost
import h.Hchat.hooks.api.sns.WeChatSnsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.div

/** 微信单条朋友圈最多 9 张图, 导出表格按固定列展开, 方便直接筛选/统计。 */
private const val MAX_IMAGE_COLUMNS = 9

/** 进度回调的最小间隔, 避免逐条导出时把主线程刷爆。 */
private const val PROGRESS_THROTTLE_MILLIS = 60L

private val CSV_HEADER: List<String> = ArrayList<String>(7 + MAX_IMAGE_COLUMNS + 4).apply {
    add("snsId")
    add("发布时间")
    add("作者微信ID")
    add("作者昵称")
    add("类型")
    add("文案")
    add("媒体数量")
    repeat(MAX_IMAGE_COLUMNS) { add("图片${it + 1}") }
    add("视频")
    add("实况视频")
    add("视频封面")
    add("媒体导出失败")
}

private enum class ExportFormat(val displayName: String, val ext: String) {
    TXT("纯文本 (.txt)", "txt"),
    CSV("表格 (.csv，按文案/图片分列)", "csv"),
    JSON("JSON (.json)", "json")
}

private enum class LimitOption(val displayName: String, val value: Int) {
    RECENT_100("最近 100 条", 100),
    RECENT_500("最近 500 条", 500),
    RECENT_1000("最近 1000 条", 1000),
    ALL("全部 (最多 2000 条)", 2000)
}

private data class ExportOptions(
    val format: ExportFormat,
    val limit: LimitOption,
    val includeMedia: Boolean,
    val includeVideo: Boolean
)

/** 按类型归类的导出结果, 供 CSV 分列与 JSON 分组使用。 */
private data class MediaExport(
    val imageFiles: List<String> = emptyList(),
    val liveVideoFiles: List<String> = emptyList(),
    val videoFiles: List<String> = emptyList(),
    val coverFiles: List<String> = emptyList(),
    val failure: String? = null
) {
    val count: Int get() = imageFiles.size + liveVideoFiles.size + videoFiles.size + coverFiles.size
}

private data class ExportProgress(
    val phase: String,
    val detail: String,
    val completed: Int,
    val total: Int
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

private data class ExportSummary(
    val success: Boolean,
    val message: String,
    val rootPath: String = "",
    val exportedUsers: Int = 0,
    val totalPosts: Int = 0,
    val totalMedia: Int = 0,
    val canceled: Boolean = false
)

private data class UserBundle(
    val wxId: String,
    val displayName: String,
    val posts: List<WeChatSnsPost>
)

/**
 * 边导边写的文件句柄: 每 append 一条就 flush, 这样进程被杀或用户取消时,
 * 已经导出的朋友圈仍然留在文件里, 而不是等整批结束才一次性落盘。
 */
private class MomentsFileWriter(
    val outputFile: File,
    private val format: ExportFormat,
    private val displayName: String,
    private val wxId: String,
    private val exportTime: Date
) : Closeable {

    private val writer = BufferedWriter(OutputStreamWriter(outputFile.outputStream(), Charsets.UTF_8))
    private var appended = 0

    init {
        // Excel 只会按 BOM 判定 UTF-8, 否则中文列名和文案会乱码。
        if (format == ExportFormat.CSV) writer.write("\uFEFF")
        when (format) {
            ExportFormat.TXT -> {
                writer.write("朋友圈导出: $displayName ($wxId)")
                writer.write("\n")
                writer.write("导出时间: ${formatDateTime(exportTime)}")
                writer.write("\n\n")
            }

            ExportFormat.CSV -> {
                writer.write(CSV_HEADER.joinToString(",") { csvCell(it) })
                writer.write("\n")
            }

            ExportFormat.JSON -> {
                writer.write("{")
                writer.write("\n")
                writer.write("  \"userName\": ${jsonString(wxId)},")
                writer.write("\n")
                writer.write("  \"displayName\": ${jsonString(displayName)},")
                writer.write("\n")
                writer.write("  \"exportedAt\": ${jsonString(formatDateTime(exportTime))},")
                writer.write("\n")
                writer.write("  \"moments\": [")
            }
        }
        writer.flush()
    }

    fun append(post: WeChatSnsPost, media: MediaExport) {
        when (format) {
            ExportFormat.TXT -> appendText(post, media)
            ExportFormat.CSV -> appendCsv(post, media)
            ExportFormat.JSON -> appendJson(post, media)
        }
        writer.flush()
        appended++
    }

    override fun close() {
        runCatching {
            if (format == ExportFormat.JSON) {
                writer.write("\n  ],")
                writer.write("\n")
                writer.write("  \"count\": $appended")
                writer.write("\n")
                writer.write("}")
                writer.write("\n")
            }
            writer.flush()
        }
        runCatching { writer.close() }
    }

    private fun appendText(post: WeChatSnsPost, media: MediaExport) {
        writer.write("=".repeat(60))
        writer.write("\n")
        writer.write("[$appended] [${formatDateTime(Date(post.getCreateTimeMillis()))}] ${typeDisplayName(post)}")
        writer.write("\n")
        val text = post.getText()
        if (text.isNotBlank()) {
            writer.write("[文案]")
            writer.write("\n")
            writer.write(text)
            writer.write("\n")
        }
        val images = media.imageFiles
        if (images.isNotEmpty()) {
            writer.write("[图片] ${images.size} 张")
            writer.write("\n")
            images.forEachIndexed { index, name ->
                writer.write("  图片${index + 1}: $name")
                writer.write("\n")
            }
        }
        media.liveVideoFiles.forEach { writer.write("[实况视频] $it\n") }
        media.videoFiles.forEach { writer.write("[视频] $it\n") }
        media.coverFiles.forEach { writer.write("[视频封面] $it\n") }
        val failure = media.failure
        if (!failure.isNullOrBlank()) {
            writer.write("[媒体未导出] $failure")
            writer.write("\n")
        }
        writer.write("\n")
    }

    private fun appendCsv(post: WeChatSnsPost, media: MediaExport) {
        val images = media.imageFiles
        val cells = ArrayList<String>(CSV_HEADER.size).apply {
            add(post.getSnsId())
            add(formatDateTime(Date(post.getCreateTimeMillis())))
            add(post.getUserName())
            add(post.getDisplayName())
            add(typeDisplayName(post))
            add(post.getText())
            add(media.count.toString())
            repeat(MAX_IMAGE_COLUMNS) { add(images.getOrNull(it).orEmpty()) }
            add(media.videoFiles.joinToString("; "))
            add(media.liveVideoFiles.joinToString("; "))
            add(media.coverFiles.joinToString("; "))
            add(media.failure.orEmpty())
        }
        writer.write(cells.joinToString(",") { csvCell(it) })
        writer.write("\n")
    }

    private fun appendJson(post: WeChatSnsPost, media: MediaExport) {
        if (appended > 0) writer.write(",")
        writer.write("\n")
        writer.write("    {")
        writer.write("\n")
        writer.write("      \"snsId\": ${jsonString(post.getSnsId())},")
        writer.write("\n")
        writer.write("      \"localId\": ${post.getLocalId()},")
        writer.write("\n")
        writer.write("      \"userName\": ${jsonString(post.getUserName())},")
        writer.write("\n")
        writer.write("      \"displayName\": ${jsonString(post.getDisplayName())},")
        writer.write("\n")
        writer.write("      \"createTime\": ${jsonString(formatDateTime(Date(post.getCreateTimeMillis())))},")
        writer.write("\n")
        writer.write("      \"createTimeSeconds\": ${post.getCreateTimeSeconds()},")
        writer.write("\n")
        writer.write("      \"type\": ${jsonString(post.getType())},")
        writer.write("\n")
        writer.write("      \"text\": ${jsonString(post.getText())},")
        writer.write("\n")
        writer.write("      \"mediaUrls\": [")
        writer.write(post.getMediaList().joinToString(", ") { jsonString(it.getUrl()) })
        writer.write("],")
        writer.write("\n")
        writer.write("      \"images\": [")
        writer.write(media.imageFiles.joinToString(", ") { jsonString(it) })
        writer.write("],")
        writer.write("\n")
        writer.write("      \"videos\": [")
        writer.write(media.videoFiles.joinToString(", ") { jsonString(it) })
        writer.write("],")
        writer.write("\n")
        writer.write("      \"livePhotoVideos\": [")
        writer.write(media.liveVideoFiles.joinToString(", ") { jsonString(it) })
        writer.write("],")
        writer.write("\n")
        writer.write("      \"videoCovers\": [")
        writer.write(media.coverFiles.joinToString(", ") { jsonString(it) })
        writer.write("],")
        writer.write("\n")
        writer.write("      \"mediaError\": ${jsonString(media.failure.orEmpty())}")
        writer.write("\n")
        writer.write("    }")
    }
}

@Feature(
    name = "导出朋友圈",
    categories = ["朋友圈"],
    description = "导出指定好友的朋友圈到 Download/WCX，支持 TXT / CSV / JSON，可选一并导出图片与视频\n" +
        "CSV 按「文案 / 图片 1-9 / 视频 / 实况视频」分列，可直接用 Excel 打开\n" +
        "边导边写：每导出一条就落盘一条，中途取消也会保留已导出内容\n" +
        "内容来源为微信本地缓存，未缓存到本机的朋友圈无法导出"
)
object ExportUserMoments : ClickableFeature() {

    private const val TAG = "ExportUserMoments"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = runCatching { WeDatabaseApi.getFriends() }
            .onFailure { WeLogger.e(TAG, "读取好友列表失败", it) }
            .getOrDefault(emptyList())

        showComposeDialog(context) {
            var selectedWxIds by remember { mutableStateOf(emptySet<String>()) }
            var step by remember { mutableIntStateOf(0) }
            var selectedFormat by remember { mutableStateOf(ExportFormat.TXT) }
            var selectedLimit by remember { mutableStateOf(LimitOption.RECENT_500) }
            var includeMedia by remember { mutableStateOf(true) }
            // 视频默认跳过: 未缓存到本机的视频在微信侧会阻塞约 90 秒后才失败。
            var includeVideo by remember { mutableStateOf(false) }
            var progress by remember { mutableStateOf(ExportProgress("准备中", "", 0, 0)) }
            var summary by remember { mutableStateOf<ExportSummary?>(null) }
            var canceledFlag by remember { mutableStateOf<AtomicBoolean?>(null) }

            when (step) {
                0 -> ContactsSelector(
                    title = "选择要导出朋友圈的好友",
                    contacts = contacts,
                    initialSelectedWxIds = emptySet(),
                    onDismiss = onDismiss,
                    onConfirm = { wxIds ->
                        if (wxIds.isEmpty()) {
                            showToast("请选择至少一位好友")
                            return@ContactsSelector
                        }
                        selectedWxIds = wxIds
                        step = 1
                    }
                )

                1 -> AlertDialogContent(
                    title = { Text("导出选项") },
                    text = {
                        DefaultColumn(scrollable = true) {
                            Text("已选择 ${selectedWxIds.size} 位好友")
                            HorizontalDivider()
                            Text("导出格式")
                            ExportFormat.values().forEach { format ->
                                TextButton(onClick = { selectedFormat = format }) {
                                    val icon = if (selectedFormat == format) "● " else "○ "
                                    Text(icon + format.displayName)
                                }
                            }
                            HorizontalDivider()
                            Text("导出数量")
                            LimitOption.values().forEach { limitOption ->
                                TextButton(onClick = { selectedLimit = limitOption }) {
                                    val icon = if (selectedLimit == limitOption) "● " else "○ "
                                    Text(icon + limitOption.displayName)
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("同时导出图片与视频")
                                Switch(
                                    checked = includeMedia,
                                    onCheckedChange = { includeMedia = it }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("导出视频")
                                Switch(
                                    checked = includeVideo,
                                    enabled = includeMedia,
                                    onCheckedChange = { includeVideo = it }
                                )
                            }
                            Text(
                                "视频若未缓存到本机，微信侧要等约 90 秒才判定失败，建议保持关闭",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "文件将保存到: Download/WCX/moments_<时间>/<好友>/",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    dismissButton = {
                        UiTextButton(onClick = { step = 0 }) { Text("上一步") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val options = ExportOptions(
                                selectedFormat, selectedLimit, includeMedia, includeVideo
                            )
                            val targets = selectedWxIds.toList()
                            val cancelToken = AtomicBoolean(false)
                            canceledFlag = cancelToken
                            progress = ExportProgress("准备中", "正在读取朋友圈缓存...", 0, 0)
                            summary = null
                            step = 2
                            // 独立于对话框生命周期的 scope: 关掉对话框导出仍继续,
                            // 只有点「取消」才会中断。
                            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                                val result = runCatching {
                                    runExport(targets, options, cancelToken) { snapshot ->
                                        withContext(Dispatchers.Main) { progress = snapshot }
                                    }
                                }.onFailure {
                                    WeLogger.e(TAG, "导出朋友圈异常", it)
                                }.getOrElse {
                                    ExportSummary(success = false, message = "导出失败: ${it.message}")
                                }
                                withContext(Dispatchers.Main) {
                                    summary = result
                                    step = 3
                                }
                                showToastSuspend(result.message)
                            }
                        }) { Text("导出") }
                    }
                )

                2 -> AlertDialogContent(
                    title = { Text("正在导出") },
                    text = {
                        DefaultColumn {
                            Text(progress.phase, style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(progress.detail, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${(progress.fraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (progress.total > 0) {
                                Text(
                                    "${progress.completed} / ${progress.total}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "已导出的内容会实时写入文件，取消后仍会保留",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    dismissButton = {
                        UiTextButton(onClick = { canceledFlag?.set(true) }) { Text("取消") }
                    }
                )

                else -> {
                    val result = summary
                    AlertDialogContent(
                        title = { Text(if (result?.success == true) "导出完成" else "导出结束") },
                        text = {
                            DefaultColumn {
                                Text(result?.message.orEmpty())
                                if (!result?.rootPath.isNullOrBlank()) {
                                    Text(
                                        result.rootPath,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = onDismiss) { Text("完成") }
                        }
                    )
                }
            }
        }
    }

    private suspend fun runExport(
        wxIds: List<String>,
        options: ExportOptions,
        canceled: AtomicBoolean,
        onProgress: suspend (ExportProgress) -> Unit
    ): ExportSummary = withContext(Dispatchers.IO) {
        var lastProgressAt = 0L
        suspend fun emit(snapshot: ExportProgress, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastProgressAt < PROGRESS_THROTTLE_MILLIS) return
            lastProgressAt = now
            onProgress(snapshot)
        }

        val snsApi = WeChatApis.snsApi()
            ?: return@withContext ExportSummary(
                success = false,
                message = "朋友圈服务尚未就绪，请重启微信后重试"
            )

        WeLogger.i(
            TAG,
            "导出开始: 好友=${wxIds.size} 格式=${options.format.name} 上限=${options.limit.value} " +
                "含媒体=${options.includeMedia} 含视频=${options.includeVideo}"
        )

        emit(ExportProgress("读取朋友圈缓存", "正在预热微信朋友圈存储...", 0, wxIds.size), force = true)
        val storageReady = runCatching { snsApi.warmupCachedPosts() }
            .onFailure { WeLogger.e(TAG, "朋友圈缓存存储预热失败", it) }
            .getOrDefault(false)
        WeLogger.i(TAG, "朋友圈缓存存储预热结果: $storageReady")

        // 阶段一: 先把所有好友的朋友圈读出来, 这样总体进度才有准确分母。
        val bundles = ArrayList<UserBundle>(wxIds.size)
        for ((index, wxId) in wxIds.withIndex()) {
            if (canceled.get()) {
                return@withContext ExportSummary(success = false, message = "已取消", canceled = true)
            }
            emit(
                ExportProgress("读取朋友圈缓存", "正在读取 $wxId", index, wxIds.size),
                force = true
            )
            val posts = runCatching { snsApi.getSnsPostList(wxId, options.limit.value) }
                .onFailure { WeLogger.e(TAG, "读取好友朋友圈失败: $wxId", it) }
                .getOrNull()
                .orEmpty()
            WeLogger.i(TAG, "读取 $wxId 完成: ${posts.size} 条")
            if (posts.isEmpty()) continue

            val displayName = posts.firstOrNull()?.getDisplayName().orEmpty()
                .ifBlank { runCatching { WeDatabaseApi.getDisplayName(wxId) }.getOrDefault("") }
                .ifBlank { wxId }
            bundles += UserBundle(wxId, displayName, posts)
        }

        val totalPosts = bundles.sumOf { it.posts.size }
        if (totalPosts == 0) {
            WeLogger.w(TAG, "没有可导出的朋友圈, 缓存是否就绪=$storageReady")
            return@withContext ExportSummary(
                success = false,
                message = if (!storageReady) {
                    "无法读取本机朋友圈缓存（DEX 定位失败），请重启微信后重试"
                } else {
                    "所选好友没有可导出的朋友圈，未缓存到本机的朋友圈无法导出"
                }
            )
        }

        val exportTime = Date()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(exportTime)
        val rootDir = (KnownPaths.downloads / "moments_$timeStamp").toFile()
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            WeLogger.e(TAG, "无法创建导出目录: ${rootDir.absolutePath}")
            return@withContext ExportSummary(
                success = false,
                message = "无法创建导出目录: ${rootDir.absolutePath}"
            )
        }
        WeLogger.i(TAG, "导出目录: ${rootDir.absolutePath}, 总条数=$totalPosts")

        // 阶段二: 逐好友导出, 每条媒体完成后立刻写一行并 flush。
        var processedPosts = 0
        var totalMedia = 0
        var mediaIssues = 0
        var exportedUsers = 0
        val startAt = SystemClock.elapsedRealtime()
        var canceledMidway = false

        outer@ for (bundle in bundles) {
            if (canceled.get()) {
                canceledMidway = true
                break@outer
            }

            val userDir = File(rootDir, "${safeFileName(bundle.displayName)}_${safeFileName(bundle.wxId)}")
            if (!userDir.exists() && !userDir.mkdirs()) {
                WeLogger.w(TAG, "无法创建好友导出目录: ${userDir.absolutePath}")
                processedPosts += bundle.posts.size
                continue
            }

            val outputFile = File(userDir, "moments.${options.format.ext}")
            val fileWriter = runCatching {
                MomentsFileWriter(outputFile, options.format, bundle.displayName, bundle.wxId, exportTime)
            }.onFailure {
                WeLogger.e(TAG, "创建导出文件失败: ${outputFile.absolutePath}", it)
            }.getOrNull()
            if (fileWriter == null) {
                processedPosts += bundle.posts.size
                continue
            }

            val mediaDir = File(userDir, "media")
            WeLogger.i(TAG, "开始写入 ${outputFile.absolutePath}")

            try {
                for (post in bundle.posts) {
                    if (canceled.get()) {
                        canceledMidway = true
                        break@outer
                    }

                    val position = processedPosts + 1
                    WeLogger.i(
                        TAG,
                        "[$position/$totalPosts] type=${post.getType()} " +
                            "time=${formatDateTime(Date(post.getCreateTimeMillis()))} " +
                            "snsId=${post.getSnsId()} 开始导出媒体"
                    )

                    val media = if (options.includeMedia) {
                        exportPostMedia(snsApi, post, mediaDir, options.includeVideo)
                    } else {
                        MediaExport()
                    }
                    if (media.failure != null) mediaIssues++
                    totalMedia += media.count

                    fileWriter.append(post, media)
                    processedPosts++
                    emit(
                        ExportProgress(
                            phase = "导出 ${bundle.displayName}",
                            detail = "第 $processedPosts/$totalPosts 条 · ${typeDisplayName(post)}",
                            completed = processedPosts,
                            total = totalPosts
                        )
                    )
                }
            } finally {
                fileWriter.close()
            }

            exportedUsers++
            WeLogger.i(
                TAG,
                "写入完成: ${outputFile.absolutePath} (${outputFile.length()} 字节, " +
                    "已处理 $processedPosts/$totalPosts)"
            )
        }

        emit(
            ExportProgress(
                if (canceledMidway) "已取消" else "完成",
                "共处理 $processedPosts 条",
                processedPosts,
                totalPosts
            ),
            force = true
        )
        val elapsed = SystemClock.elapsedRealtime() - startAt
        val message = buildString {
            if (canceledMidway) append("已取消，") else append("已完成，")
            append("$exportedUsers/${bundles.size} 位好友，$processedPosts/$totalPosts 条")
            if (options.includeMedia) {
                append("，媒体 $totalMedia 个")
                if (mediaIssues > 0) append("（$mediaIssues 条未导出媒体）")
            }
        }
        WeLogger.i(TAG, "导出结束: $message 耗时=${elapsed}ms 目录=${rootDir.absolutePath}")
        ExportSummary(
            success = exportedUsers > 0,
            message = message,
            rootPath = rootDir.absolutePath,
            exportedUsers = exportedUsers,
            totalPosts = processedPosts,
            totalMedia = totalMedia,
            canceled = canceledMidway
        )
    }

    /**
     * 通过微信原生的朋友圈转发准备流程把图片/视频解码到本地缓存, 再复制到导出目录。
     * 只有图片、视频与实况照片支持, 图文卡片类朋友圈会返回空结果。
     */
    private fun exportPostMedia(
        snsApi: WeChatSnsApi,
        post: WeChatSnsPost,
        mediaDir: File,
        includeVideo: Boolean
    ): MediaExport {
        val type = post.getType()
        if (type != WeChatSnsPost.TYPE_IMAGE &&
            type != WeChatSnsPost.TYPE_VIDEO &&
            type != WeChatSnsPost.TYPE_LIVE_PHOTO
        ) {
            return MediaExport()
        }
        // 视频未缓存到本机时微信侧要阻塞 ~90 秒才报「朋友圈视频下载失败」,
        // 因此默认直接跳过, 由用户在选项里显式开启。
        if (type == WeChatSnsPost.TYPE_VIDEO && !includeVideo) {
            return MediaExport(failure = "已跳过视频（未开启「导出视频」）")
        }

        val prepared = runCatching { snsApi.prepareSnsPostMedia(post.getSnsId()) }
            .onFailure { WeLogger.e(TAG, "准备朋友圈媒体失败: ${post.getSnsId()}", it) }
            .getOrNull()
        if (prepared == null || !prepared.isSuccess()) {
            val reason = prepared?.getMessage() ?: "媒体准备失败"
            WeLogger.w(TAG, "媒体准备失败 snsId=${post.getSnsId()}: $reason")
            return MediaExport(failure = reason)
        }

        if (!mediaDir.exists() && !mediaDir.mkdirs()) {
            return MediaExport(failure = "无法创建媒体目录")
        }

        val baseName = mediaBaseName(post)
        val imageFiles = mutableListOf<String>()
        val liveVideoFiles = mutableListOf<String>()
        val videoFiles = mutableListOf<String>()
        val coverFiles = mutableListOf<String>()
        val livePhotos = prepared.getLivePhotoList().associateBy { it.getImagePath() }
        val imagePaths = prepared.getImagePathList()

        imagePaths.forEachIndexed { index, sourcePath ->
            val suffix = if (imagePaths.size > 1) "_$index" else ""
            val imageName = "$baseName$suffix.${extensionOf(sourcePath, "jpg")}"
            if (copyMediaFile(sourcePath, File(mediaDir, imageName))) {
                imageFiles += imageName
            }
            val livePhoto = livePhotos[sourcePath] ?: return@forEachIndexed
            val liveName = "$baseName$suffix.${extensionOf(livePhoto.getVideoPath(), "mp4")}"
            if (copyMediaFile(livePhoto.getVideoPath(), File(mediaDir, liveName))) {
                liveVideoFiles += liveName
            }
        }

        val videoPath = prepared.getVideoPath()
        if (videoPath.isNotBlank()) {
            val videoName = "$baseName.${extensionOf(videoPath, "mp4")}"
            if (copyMediaFile(videoPath, File(mediaDir, videoName))) {
                videoFiles += videoName
            }
        }

        val videoThumbPath = prepared.getVideoThumbPath()
        if (videoThumbPath.isNotBlank()) {
            val thumbName = "${baseName}_cover.${extensionOf(videoThumbPath, "jpg")}"
            if (copyMediaFile(videoThumbPath, File(mediaDir, thumbName))) {
                coverFiles += thumbName
            }
        }

        val total = imageFiles.size + liveVideoFiles.size + videoFiles.size + coverFiles.size
        return when {
            total > 0 -> MediaExport(imageFiles, liveVideoFiles, videoFiles, coverFiles)
            imagePaths.isEmpty() && videoPath.isBlank() -> MediaExport(failure = "未找到可用的媒体文件")
            else -> MediaExport(failure = "媒体文件复制失败")
        }
    }

    private fun copyMediaFile(sourcePath: String, target: File): Boolean {
        if (sourcePath.isBlank()) return false
        val copied = runCatching { WeMomentsApi.copyExistingFile(sourcePath, target.absolutePath) }
            .onFailure { WeLogger.e(TAG, "复制媒体文件失败: $sourcePath", it) }
            .getOrDefault(false)
        if (copied && target.isFile && target.length() > 0) return true

        val source = File(sourcePath)
        if (!source.isFile) {
            WeLogger.w(TAG, "媒体源文件不存在: $sourcePath")
            return false
        }
        return runCatching {
            source.copyTo(target, overwrite = true)
            target.isFile && target.length() > 0
        }.onFailure {
            WeLogger.e(TAG, "回退复制媒体文件失败: $sourcePath", it)
        }.getOrDefault(false)
    }
}

private fun mediaBaseName(post: WeChatSnsPost): String {
    val millis = post.getCreateTimeMillis().takeIf { it > 0 } ?: System.currentTimeMillis()
    return "${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(millis))}" +
        "_${safeFileName(post.getSnsId())}"
}

private fun extensionOf(path: String, fallback: String): String {
    val name = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "")
    return extension
        .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        ?.lowercase(Locale.ROOT)
        ?: fallback
}

private fun typeDisplayName(post: WeChatSnsPost): String = when (post.getType()) {
    WeChatSnsPost.TYPE_TEXT -> "文本"
    WeChatSnsPost.TYPE_IMAGE -> "图片"
    WeChatSnsPost.TYPE_VIDEO -> "视频"
    WeChatSnsPost.TYPE_LIVE_PHOTO -> "实况照片"
    WeChatSnsPost.TYPE_CARD -> "图文卡片"
    else -> "未知"
}

private fun formatDateTime(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)

private fun safeFileName(raw: String): String {
    val cleaned = raw.map { ch ->
        if (ch in "\\/:*?\"<>|" || ch.isISOControl()) '_' else ch
    }.joinToString("").trim().trimEnd('.', ' ')
    return cleaned.ifBlank { "unknown" }.take(60)
}

private fun csvCell(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
