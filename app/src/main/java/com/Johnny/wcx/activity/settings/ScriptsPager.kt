package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.ui.miuix.ScriptPluginMiuixTabContent
import h.Hchat.ui.miuix.ScriptPluginReadmeTabDialog

/**
 * 「脚本」独立页 —— WCX 专属脚本插件中心。
 *
 * 子页面（在线插件 / 本地插件管理 / 脚本功能诊断）通过设置页导航栈整页跳转，
 * 与 Hchat 的观感一致（不再使用 Dialog 覆盖层）。
 */
@Composable
fun ScriptsPager(
    onOpenMarket: () -> Unit = {},
    onOpenManager: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenFaq: () -> Unit = {},
) {
    val context = LocalContext.current
    var readmePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }

    MiuixListScaffold(title = "脚本", blurTopBar = false, horizontalPadding = 0.dp) {
        item {
            // 父级 Pager 与内层 TopAppBar 各有一层 backdrop；内容滚动越界会被采样成顶部灰块，这里裁剪掉溢出绘制。
            Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                ScriptPluginMiuixTabContent(
                    context = context,
                    onOpenManager = onOpenManager,
                    onOpenReadme = { plugin -> readmePlugin = plugin },
                    onOpenMarket = onOpenMarket,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenFaq = onOpenFaq,
                )
            }
        }
        item {
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }

    val pending = readmePlugin
    if (pending != null) {
        ScriptPluginReadmeTabDialog(
            context = context,
            plugin = pending,
            onClose = { readmePlugin = null },
        )
    }
}
