package h.Hchat.hooks.core

import android.content.Context
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.event.EventBus
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.UIRegistry
import org.luckypray.dexkit.DexKitBridge

/**
 * 功能模块运行时上下文。
 */
class FeatureContext(
    private val hostContext: Context,
    private val moduleContext: Context,
    private val hostClassLoader: ClassLoader,
    // 注：WCX 只跑在 libxposed(lsp10x) 入口下，宿主不会产生 legacy Xposed 的
    // LoadPackageParam 对象；且本对象在全仓库没有任何读取方，故类型放宽为 Any?，
    // 由引导层传 null —— 避免为了构造它而中断整条脚本引导链路。
    private val loadPackageParam: Any?,
    private val dexKitBridge: DexKitBridge,
    private val dexFinder: DexFinder,
    private val eventBus: EventBus,
    private val configStore: ConfigStore,
    private val dexBridgeHolder: DexBridgeHolder,
    private val uiRegistry: UIRegistry
) {
    fun hostContext(): Context = hostContext

    fun moduleContext(): Context = moduleContext

    fun hostClassLoader(): ClassLoader = hostClassLoader

    fun loadPackageParam(): Any? = loadPackageParam

    fun dexKitBridge(): DexKitBridge = dexKitBridge

    fun dexFinder(): DexFinder = dexFinder

    fun eventBus(): EventBus = eventBus

    fun configStore(): ConfigStore = configStore

    fun dexBridgeHolder(): DexBridgeHolder = dexBridgeHolder

    fun uiRegistry(): UIRegistry = uiRegistry
}
