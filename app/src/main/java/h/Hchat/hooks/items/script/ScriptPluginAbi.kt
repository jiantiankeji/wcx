package h.Hchat.hooks.items.script

import me.hd.wauxv.data.bean.MsgInfoBean
import me.hd.wauxv.plugin.api.callback.PluginCallBack

/**
 * 脚本 ABI 强引用锚点。
 *
 * 这些类只被脚本运行时以“字符串/前置代码”的方式按类名引用（BeanShell 的 import
 * 与 `PluginCallBack.HttpCallback` 等方法签名），R8 静态分析看不到这类引用，会在
 * release 构建中裁剪或改名，导致脚本加载时抛出：
 *   「Class: PluginCallBack.HttpCallback not found in namespace」
 *
 * 这里以真实类型引用强制保留，配合 proguard 的 -keep 规则形成双保险。
 */
internal object ScriptPluginAbi {

    @JvmStatic
    fun anchor(): Array<Class<*>> = arrayOf(
        MsgInfoBean::class.java,
        PluginCallBack::class.java,
        PluginCallBack.HttpCallback::class.java,
        PluginCallBack.DownloadCallback::class.java,
    )
}
