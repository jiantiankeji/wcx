package com.Johnny.wcx.activity.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.items.secret_friend.HideConversations
import com.Johnny.wcx.features.items.secret_friend.SecretFriendDiagnostics
import com.Johnny.wcx.features.items.secret_friend.SecretFriendState
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.ui.miuix.ActionRow
import h.Hchat.ui.miuix.InsetDivider
import h.Hchat.ui.miuix.PageScaffold
import h.Hchat.ui.miuix.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 密友功能诊断页：逐面显示开关状态与关键锚点是否解析成功，
 * 一眼区分「开关没开」与「当前微信版本锚点漂移」。
 *
 * 支持：进入自动检测、手动「开始检测」（带进度条逐项推进）、每项点开看详情、「一键修复」。
 */
@Composable
fun SecretFriendDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var rows by remember { mutableStateOf(listOf<SecretFriendDiagnostics.DiagRow>()) }
    var lastScan by remember { mutableStateOf("尚未检测") }
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runSubtitle by remember { mutableStateOf("") }
    var runLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    fun launchScan(
        title: String,
        stepsProvider: () -> List<DiagStep>,
        showOverlay: Boolean,
        reportTitle: String?,
        replaceRows: Boolean,
    ) {
        if (running) return
        if (showOverlay) {
            running = true
            runTitle = title
            runProgress = 0f
            runSubtitle = "准备中…"
            runLog = emptyList()
        }
        scope.launch {
            val steps = runCatching { stepsProvider() }.getOrElse { emptyList() }
            val collected = mutableListOf<SecretFriendDiagnostics.DiagRow>()
            steps.forEachIndexed { index, step ->
                if (showOverlay) runSubtitle = "正在检测：${step.title}"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }.getOrElse { error ->
                        listOf(
                            SecretFriendDiagnostics.DiagRow(
                                label = step.title, section = step.title,
                                value = "检测异常", ok = false,
                                detail = "执行「${step.title}」时抛出异常：\n" + error.toString(),
                            )
                        )
                    }
                }
                collected += outcome
                if (showOverlay) {
                    runLog = runLog + (step.title to outcome.all { it.ok })
                    runProgress = (index + 1).toFloat() / steps.size.toFloat().coerceAtLeast(1f)
                    delay(120)
                }
            }
            if (replaceRows) rows = collected
            running = false
            lastScan = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (reportTitle != null) showDiagReport(context, reportTitle, collected)
        }
    }

    LaunchedEffect(Unit) {
        launchScan("密友功能诊断", { secretFriendDiagSteps() }, showOverlay = false, reportTitle = null, replaceRows = true)
    }

    fun openDetail(row: SecretFriendDiagnostics.DiagRow) {
        val builder = AlertDialog.Builder(context)
            .setTitle(row.label)
            .setMessage("状态：" + row.value + "\n\n" + row.detail)
            .setNegativeButton("关闭", null)
        val act = row.action
        if (act != null) {
            builder.setPositiveButton(row.actionLabel ?: "执行") { _, _ ->
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching { act() }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                    }
                    showToast(msg)
                    launchScan("密友功能诊断", { secretFriendDiagSteps() }, showOverlay = false, reportTitle = null, replaceRows = true)
                }
            }
        }
        builder.show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = "密友功能诊断",
            largeTitle = "密友功能诊断",
            scrollBehavior = scrollBehavior,
            onBack = onBack,
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 84.dp,
                ),
            ) {
                item {
                    Text(
                        text = "最后检测：$lastScan　·　点击任意一项可查看详情",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                rows.groupBy { it.section }.forEach { (section, list) ->
                    item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = section) }
                    item {
                        SettingsCard {
                            list.forEachIndexed { index, row ->
                                if (index > 0) InsetDivider()
                                DiagItemRow(row) { openDetail(row) }
                            }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "检测与修复") }
                item {
                    SettingsCard {
                        ActionRow("开始检测", "逐面重新扫描开关状态与锚点，带进度条") {
                            launchScan("密友功能诊断", { secretFriendDiagSteps() }, showOverlay = true, reportTitle = "密友诊断结果", replaceRows = true)
                        }
                        InsetDivider()
                        ActionRow("一键修复", "隐藏面重新对账 / 刷新会话列表 / 重读密友名单") {
                            launchScan("一键修复", { secretFriendRepairSteps() }, showOverlay = true, reportTitle = "修复结果", replaceRows = false)
                        }
                        InsetDivider()
                        ActionRow("复制诊断信息", "复制到剪贴板，便于反馈") {
                            val text = if (rows.isEmpty()) "（尚无诊断结果）"
                            else rows.joinToString("\n") { "${it.label}：${it.value}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 密友诊断", text))
                            showToast("诊断信息已复制")
                        }
                    }
                }

                item { DiagnosticLogCard(context) }
                item {
                    Text(
                        text = "说明：若某隐藏面显示「开启」却仍能看到密友，多为该面依赖的类/方法在当前微信版本已漂移" +
                            "（见「锚点自检」里标红/未找到的项）。点开每一项可看含义与恢复建议；把本页截图反馈即可定位。",
                        fontSize = 12.sp,
                        color = BasicComponentDefaults.summaryColor().color,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (running) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxWidth(0.88f).padding(16.dp)) {
                    SettingsCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = runTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(runProgress.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF2E7DFF)),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "${(runProgress * 100).toInt()}%  $runSubtitle",
                                fontSize = 12.sp,
                                color = BasicComponentDefaults.summaryColor().color,
                            )
                            Spacer(Modifier.height(12.dp))
                            runLog.takeLast(6).forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = if (entry.second) "✅ " else "❌ ", fontSize = 12.sp)
                                    Text(
                                        text = entry.first,
                                        fontSize = 12.sp,
                                        color = if (entry.second) BasicComponentDefaults.summaryColor().color
                                        else Color(0xFFD93025),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  步骤模型
// ---------------------------------------------------------------------------

private class DiagStep(val title: String, val run: () -> List<SecretFriendDiagnostics.DiagRow>)

/** 逐面自检步骤（按分组拆成可推进的几步）。 */
private fun secretFriendDiagSteps(): List<DiagStep> = listOf(
    DiagStep("总开关与名单") { SecretFriendDiagnostics.collectDetailRows().filter { it.section == "总开关与名单" } },
    DiagStep("各隐藏面") { SecretFriendDiagnostics.collectDetailRows().filter { it.section == "各隐藏面" } },
    DiagStep("锚点自检") { SecretFriendDiagnostics.collectDetailRows().filter { it.section == "锚点自检" } },
)

/** 修复步骤：产出一条结果行。 */
private fun secretFriendRepairSteps(): List<DiagStep> = listOf(
    DiagStep("隐藏面重新对账") {
        val ok = runCatching { HideConversations.reconcileOnListChange() }.isSuccess
        listOf(
            SecretFriendDiagnostics.DiagRow(
                "隐藏面重新对账", "修复", if (ok) "成功" else "失败", ok,
                "按当前密友名单重新隐藏/恢复主页会话行。",
            )
        )
    },
    DiagStep("刷新会话列表") {
        val ok = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        listOf(
            SecretFriendDiagnostics.DiagRow(
                "刷新会话列表", "修复", if (ok) "成功" else "失败", ok,
                "重新加载微信会话列表，让隐藏/恢复立即生效。",
            )
        )
    },
    DiagStep("重排 Hook 安装任务") {
        DexInstallScheduler.ensureWarmupReady()
        DexInstallScheduler.retryAllPending()
        listOf(
            SecretFriendDiagnostics.DiagRow(
                "Hook 安装任务", "修复",
                "阶段=" + DexInstallScheduler.readyLevelName() + "，待装 " + DexInstallScheduler.pendingCount() + " 个", true,
                "强制推进到 WARMUP 并重排全部 Hook 安装任务（密友隐藏面依赖的 DexKit 钩子）。",
            )
        )
    },
    DiagStep("重新读取密友名单") {
        val count = runCatching { SecretFriendState.getStoredWxIds().size }.getOrDefault(0)
        listOf(
            SecretFriendDiagnostics.DiagRow(
                "密友名单", "修复", "当前 $count 人", true,
                "重新读取当前存储的密友名单。若为 0，请到「密友名单管理」里添加密友。",
            )
        )
    },
)

@Composable
private fun DiagItemRow(row: SecretFriendDiagnostics.DiagRow, onOpenDetail: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BasicComponentDefaults.titleColor().color,
            modifier = Modifier.fillMaxWidth(0.46f),
        )
        Text(
            text = row.value,
            fontSize = 12.sp,
            color = if (row.ok) BasicComponentDefaults.summaryColor().color else Color(0xFFD93025),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun showDiagReport(context: Context, title: String, rows: List<SecretFriendDiagnostics.DiagRow>) {
    val okCount = rows.count { it.ok }
    val sb = StringBuilder()
    sb.append("共 ").append(rows.size).append(" 项，通过 ").append(okCount)
        .append("，问题 ").append(rows.size - okCount).append("。\n\n")
    rows.forEach { row ->
        sb.append(if (row.ok) "✅ " else "❌ ").append(row.label).append('：').append(row.value).append('\n')
    }
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }
}
