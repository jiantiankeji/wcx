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
import h.Hchat.ui.miuix.ScriptPluginManagerTabPage
import h.Hchat.ui.miuix.PluginMarketUi
import h.Hchat.ui.miuix.ScriptPluginMiuixTabContent
import h.Hchat.ui.miuix.ScriptPluginReadmeTabDialog

/**
 * 「脚本」独立页 —— WCX 专属脚本插件中心：
 * 插件总开关 / 插件列表（启用开关 + 操作菜单）/ 本地插件管理页 / 在线插件市场 / README 弹窗。
 * 数据与逻辑走已内置的脚本子系统（ScriptPluginRuntime / ScriptPluginManager / PluginMarketRepository）。
 * 在线插件页为 Hchat 原版 UI 整段移植（PluginMarketUi.ScriptPluginMarketPage）。
 */
@Composable
fun ScriptsPager() {
    val context = LocalContext.current
    var showManager by remember { mutableStateOf(false) }
    var showMarket by remember { mutableStateOf(false) }
    var readmePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }

    when {
        showMarket -> PluginMarketUi.ScriptPluginMarketPage(context = context, onBack = { showMarket = false })
        showManager -> ScriptPluginManagerTabPage(context = context, onBack = { showManager = false })
        else -> MiuixListScaffold(title = "脚本", blurTopBar = false) {
            item {
                // 父级 Pager 与内层 TopAppBar 各有一层 backdrop；内容滚动越界会被采样成顶部灰块，这里裁剪掉溢出绘制。
                Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                    ScriptPluginMiuixTabContent(
                        context = context,
                        onOpenManager = { showManager = true },
                        onOpenReadme = { plugin -> readmePlugin = plugin },
                        onOpenMarket = { showMarket = true },
                    )
                }
            }
            item {
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
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
