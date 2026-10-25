package com.Johnny.wcx.activity.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text

/**
 * 诊断页内嵌「运行日志」卡片（精简可复制版）。
 *
 * 两个诊断页（模块自检 / 脚本功能诊断）共用：
 * - 顶部一行摘要：日志文件、大小、记录数、错误/警告数、队列丢弃数；
 * - 默认只展示「问题」（W/E/A 级记录，含最多 8 行堆栈），不会被高频 INFO 淹没；
 * - 「复制问题摘要」产出结构化精简文本，可直接粘贴反馈；
 * - 「复制完整日志」保留原始日志能力，供深入排查。
 */
@Composable
fun DiagnosticLogCard(
    context: Context,
    title: String = "运行日志",
    maxProblems: Int = 40,
    recentLines: Int = 300,
) {
    var digest by remember { mutableStateOf<WeLogger.LogDigest?>(null) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var onlyProblems by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            runCatching {
                WeLogger.digest(maxProblems) to WeLogger.readRecentLines(recentLines).second
            }
        }.fold(
            onSuccess = {
                digest = it.first
                recent = it.second
                readError = null
            },
            onFailure = { readError = it.message ?: it.javaClass.simpleName },
        )
    }

    val d = digest
    val summary = when {
        readError != null -> "读取日志失败：$readError"
        d == null -> "正在读取…"
        d.fileName == null -> "暂无运行日志"
        else -> "${d.fileName}｜${d.fileSizeText}｜记录 ${d.totalRecords} 条｜错误 ${d.errorCount}｜警告 ${d.warnCount}" +
            if (d.droppedCount > 0L) "｜丢弃 ${d.droppedCount}" else ""
    }

    SmallTitle(modifier = Modifier.padding(top = 10.dp), text = title)
    SettingsCard {
        ActionRow("刷新日志", summary) { refreshKey++ }
        InsetDivider()
        ActionRow(
            "显示内容",
            if (onlyProblems) "仅问题（错误/警告）· 默认，最简洁" else "全部（最近 $recentLines 行原始日志）",
        ) { onlyProblems = !onlyProblems }
        InsetDivider()
        ActionRow(
            "复制问题摘要",
            "只复制错误/警告 + 计数（紧凑、可读），适合直接粘贴反馈",
        ) {
            copyLogToClipboard(context, d?.toShareText() ?: "（暂无日志）", "问题摘要")
        }
        InsetDivider()
        ActionRow(
            "复制完整日志",
            "复制最近 ${recent.size} 行原始运行日志（超长自动截断）",
        ) {
            val full = recent.joinToString("\n")
            val capped = if (full.length > MAX_LOG_COPY_CHARS) {
                full.take(MAX_LOG_COPY_CHARS) + "\n…（已截断，完整内容见日志文件）"
            } else full
            copyLogToClipboard(context, capped, "完整日志")
        }

        val preview = if (onlyProblems) buildProblemLines(d) else recent
        if (preview.isNotEmpty()) {
            InsetDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = preview.joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 单次复制上限：手机输入法/剪贴板可承受的长度，避免「复制不全」。 */
private const val MAX_LOG_COPY_CHARS = 8000

private fun buildProblemLines(d: WeLogger.LogDigest?): List<String> {
    if (d == null) return emptyList()
    if (d.problems.isEmpty()) return listOf("最近没有错误或警告。")
    return d.problems.flatMap { problem ->
        listOf(problem.header) + problem.detail.take(8).map { "    $it" }
    }
}

private fun copyLogToClipboard(context: Context, text: String, label: String) {
    val ok = runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching false
        cb.setPrimaryClip(ClipData.newPlainText("WCX 日志", text))
        true
    }.getOrDefault(false)
    showToast(if (ok) "已复制$label（${text.length} 字）" else "复制失败")
}
