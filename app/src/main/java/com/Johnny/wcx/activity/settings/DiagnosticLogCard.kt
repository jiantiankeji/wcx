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
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * 诊断页内嵌「运行日志」卡片。
 *
 * 三个诊断页（模块自检 / 脚本功能诊断 / 密友功能诊断）共用：直接在页面内展示最近的
 * WCX 运行日志，支持刷新与一键复制，便于用户就地取日志反馈，无需再跳到「日志」页。
 */
@Composable
fun DiagnosticLogCard(
    context: Context,
    maxLines: Int = 300,
    title: String = "运行日志",
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = WeLogger.allLogFiles.firstOrNull { it.exists() }
                    ?: return@runCatching Pair(null, listOf("（暂无运行日志）"))
                Pair(file.fileName.toString(), file.readLines().takeLast(maxLines))
            }.getOrElse {
                Pair(null, listOf("读取日志失败：" + (it.message ?: it.javaClass.simpleName)))
            }
        }
        fileName = result.first
        lines = result.second
    }

    SmallTitle(modifier = Modifier.padding(top = 10.dp), text = title)
    SettingsCard {
        ActionRow(
            "刷新日志",
            "重新读取最近的运行日志（最多 $maxLines 行）" + (fileName?.let { "；当前文件：$it" } ?: ""),
        ) { refreshKey++ }
        InsetDivider()
        ActionRow(
            "复制日志",
            "把下方日志（${lines.size} 行）复制到剪贴板，可直接粘贴反馈",
        ) {
            val text = lines.joinToString("\n")
            runCatching {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("WCX 运行日志", text))
            }
            showToast("日志已复制（${lines.size} 行）")
        }
        if (lines.isNotEmpty()) {
            InsetDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = lines.joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = BasicComponentDefaults.summaryColor().color,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
