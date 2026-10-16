package com.Johnny.wcx.features.hchat

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.event.EventBus
import h.Hchat.hooks.api.core.WechatApiFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import h.Hchat.hooks.items.protobuf.ProtobufPacketFeature
import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.UIRegistry
import org.luckypray.dexkit.DexKitBridge

/**
 * Hchat 脚本子系统在 WCX 中的引导层。
 *
 * Hchat 原项目的功能模块由 `h.Hchat.hooks.core.FeatureRegistry` 统一注册、由
 * `FeatureManager` 统一安装，但其中包含大量与脚本无关的功能（防撤回、朋友圈、红包等）。
 * WCX 只需要其中的「脚本插件」能力，因此这里不复用 Hchat 的全量 FeatureRegistry，
 * 而是按脚本功能的最小闭包，手动装配 [FeatureContext] 并安装两项必需 Feature：
 *
 * 1. [WechatApiFeature] —— 初始化公共微信 API（`WeChatApis`）。脚本运行时依赖它读取
 *    联系人数据库（rcontact/chatroom）、会话列表等；不安装它，脚本总开关会一直提示
 *    「微信联系人数据库尚未就绪」。
 * 2. [ScriptPluginFeature] —— 脚本插件运行时本体（插件加载、菜单/消息 Hook 等）。
 *
 * 关键点：安装完必须调用 [DexInstallScheduler.markDexBridgeReady]，否则 BRIDGE 阶段
 * 的延迟任务（含微信 API 的 `resolveAll()` 预热）永远不会执行，数据库始终不可用。
 * 这一步在原项目中由 `FeatureManager.installAll` 尾部完成。
 */
object HchatScriptBootstrap {

    private const val TAG = "HchatScriptBootstrap"

    /** 就绪自检总时长与轮询间隔。 */
    private const val READINESS_TIMEOUT_MS = 60_000L
    private const val READINESS_POLL_INTERVAL_MS = 2_000L

    @Volatile
    private var installed = false

    /** 最近一次安装链路状态；供脚本首页诊断展示（用户截图即可反映根因）。 */
    @Volatile
    var status: String = "未执行"
        private set

    /** 持有 DexKitBridge 强引用，避免被回收；该实例独立于 WCX 的懒加载/idle 机制，长期存活供脚本运行时使用。 */
    @Volatile
    private var dexKitBridgeRef: DexKitBridge? = null

    /** 持有本次引导创建的 DexFinder，供诊断页读取解析产物。 */
    @Volatile
    private var dexFinderRef: DexFinder? = null

    /** 诊断页用：DexFinder 关键解析结果（CoreStorage getter / SqliteDB 类 / MMKernel 类）。 */
    fun dexParseInfo(): String {
        val dex = dexFinderRef ?: return "未初始化"
        return "coreStorage=" + (dex.coreStorageGetter?.name ?: "null") +
            " / sqliteDb=" + (dex.sqliteDbWrapperClass?.name ?: "null") +
            " / mmKernel=" + (dex.mmKernelClass?.name ?: "null")
    }

    /** 诊断页用：重置引导状态并重新安装（含把所有 Hook 安装任务重排、强制推进到 WARMUP）。 */
    @Synchronized
    fun retry() {
        installed = false
        status = "重新初始化中"
        install()
        // 重新初始化后：
        // 一、强制把就绪阶段推进到 WARMUP —— 否则若 wechat_api:warmup 任务没跑成，
        //     脚本的菜单 / 消息 / 发送按钮等 WARMUP 级 Hook 永远不会被尝试；
        // 二、把**所有**未安装任务（含仍在重试与已暂停的）计数清零并立即重排。
        runCatching { DexInstallScheduler.ensureWarmupReady() }
            .onFailure { WeLogger.e(TAG, "ensureWarmupReady 失败", it) }
        runCatching { DexInstallScheduler.retryAllPending() }
            .onFailure { WeLogger.e(TAG, "retryAllPending 失败", it) }
    }

    /**
     * 彻底重新初始化：重建整个引导链路，再强制推进就绪阶段、重排所有 Hook 安装任务。
     * 返回逐步执行日志（供诊断页展示）。
     */
    @Synchronized
    fun reinitializeFully(): List<String> {
        val log = mutableListOf<String>()
        installed = false
        status = "彻底重新初始化中"

        runCatching { install() }.fold(
            onSuccess = {
                log += "已重建引导链路（DexKitBridge + WechatApiFeature + ScriptPluginFeature）"
                log += "引导状态：" + status
            },
            onFailure = { log += "重建引导链路异常：" + (it.message ?: it.javaClass.simpleName) },
        )

        runCatching { DexInstallScheduler.ensureWarmupReady() }.fold(
            onSuccess = { log += "已强制推进就绪阶段到 " + DexInstallScheduler.readyLevelName() },
            onFailure = { log += "推进就绪阶段失败：" + (it.message ?: it.javaClass.simpleName) },
        )

        runCatching { DexInstallScheduler.retryAllPending() }.fold(
            onSuccess = {
                val tasks = DexInstallScheduler.snapshot()
                log += "已重排全部 Hook 安装任务（共 " + tasks.size + " 个，待装 " +
                        DexInstallScheduler.pendingCount() + " 个）"
            },
            onFailure = { log += "重排 Hook 任务失败：" + (it.message ?: it.javaClass.simpleName) },
        )

        return log
    }

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        status = "开始安装"

        runCatching {
            val hostContext = HostInfo.application
            val hostClassLoader = ClassLoaders.HOST

            val dexKitBridge = DexKitBridge.create(HostInfo.appInfo.sourceDir)
            dexKitBridgeRef = dexKitBridge
            status = "DexKitBridge 已创建"

            val dexFinder = DexFinder(dexKitBridge, hostClassLoader, hostContext)
            dexFinderRef = dexFinder
            val dexBridgeHolder = DexBridgeHolder(
                dexKitBridge,
                dexFinder,
                hostClassLoader,
                HostInfo.appInfo.sourceDir
            )

            // moduleContext：Hchat 原本通过 createPackageContext(模块包名) 获取，用于读取模块资源。
            // WCX 的脚本资源已随模块 ClassLoader 一起加载，这里直接用宿主 Context 兜底即可。
            val context = FeatureContext(
                hostContext,
                hostContext,
                hostClassLoader,
                null,
                dexKitBridge,
                dexFinder,
                EventBus.get(),
                ConfigStore(hostContext),
                dexBridgeHolder,
                UIRegistry.get()
            )

            // 与 Hchat 的 FeatureManager.installAll 保持一致：先 onInit（注册设置 provider 等），再 install。
            runCatching {
                WechatApiFeature().also { it.onInit(context) }.install(context)
            }.onSuccess {
                status = "WechatApiFeature 安装成功"
            }.onFailure {
                status = "WechatApiFeature 安装失败：" + (it.message ?: it.javaClass.simpleName)
                WeLogger.e(TAG, "WechatApiFeature 安装失败", it)
            }
            runCatching {
                ScriptPluginFeature().also { it.onInit(context) }.install(context)
            }.onSuccess {
                status += " / ScriptPluginFeature 安装成功"
            }.onFailure {
                status += " / ScriptPluginFeature 安装失败：" + (it.message ?: it.javaClass.simpleName)
                WeLogger.e(TAG, "ScriptPluginFeature 安装失败", it)
            }

            // 追加安装「脚本插件 API」依赖的 Feature。与 Hchat 的 FeatureManager.installAll 一致：
            // 先 onInit（注册设置入口），仅当启用时才 install。
            // - ProtobufPacketFeature：脚本 sendPacket / 数据包监听；
            // - FakeVoiceDurationFeature：伪造语音时长（未在设置中开启时，其 hook 内部自动跳过）。
            listOf(ProtobufPacketFeature(), FakeVoiceDurationFeature()).forEach { extra ->
                val extraName = runCatching { extra.name() }.getOrDefault(extra.javaClass.simpleName)
                runCatching {
                    extra.onInit(context)
                    if (extra.isEnabled(context)) {
                        extra.install(context)
                        status += " / $extraName 安装成功"
                    } else {
                        status += " / $extraName 未启用"
                    }
                }.onFailure {
                    status += " / $extraName 安装失败：" + (it.message ?: it.javaClass.simpleName)
                    WeLogger.e(TAG, "$extraName 安装失败", it)
                }
            }

            // 关键：推进调度器到 BRIDGE 阶段，触发 WechatApiFeature 注册的 warmup 任务
            // （resolveAll + 数据库 API 解析），数据库才会就绪。无论前面是否局部失败都要执行。
            runCatching { DexInstallScheduler.markDexBridgeReady() }
                .onFailure { WeLogger.e(TAG, "markDexBridgeReady 失败", it) }

            // 冷启动时 DexKit 预热可能较慢、或 warmup 任务偶发没跑成，导致阶段卡在 BRIDGE、
            // 脚本页显示未就绪而需要用户手动修复。这里起一个轻量自愈看门狗。
            scheduleReadyWatchdog()

            WeLogger.i(TAG, "Hchat 脚本子系统已安装（WechatApiFeature + ScriptPluginFeature + markDexBridgeReady）")

            // 自动初始化：启动一个轻量后台线程，延迟 5s 后主动调一次 ensureWarmupReady + retryAllPending。
            // 这是用户在脚本诊断页「一键修复」按钮的核心操作——把它放在启动链路里，用户不再需要手动点。
            // 不重建 DexKitBridge（install() 已成功完成），仅推进就绪阶段 + 重排未装 Hook 任务，开销可控。
            // 跑在独立 HandlerThread 上，不阻塞主线程 / 当前 Hchat-Bootstrap 线程。
            val autoInitThread = HandlerThread("WCX-Script-AutoInit").apply { start() }
            Handler(autoInitThread.looper).postDelayed({
                runCatching { DexInstallScheduler.ensureWarmupReady() }
                    .onFailure { WeLogger.e(TAG, "自动初始化 ensureWarmupReady 失败", it) }
                runCatching { DexInstallScheduler.retryAllPending() }
                    .onFailure { WeLogger.e(TAG, "自动初始化 retryAllPending 失败", it) }
                runCatching { autoInitThread.quitSafely() }
            }, 5_000L)

            // 就绪自检：把最终结果写进 WCX 日志，避免「总开关一直提示未就绪」时无从定位。
            Thread({
                val startedAt = System.currentTimeMillis()
                var detail: String? = "自检未开始"
                while (System.currentTimeMillis() - startedAt < READINESS_TIMEOUT_MS) {
                    detail = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }
                        .getOrElse { it.message ?: "自检异常" }
                    if (detail == null) break
                    Thread.sleep(READINESS_POLL_INTERVAL_MS)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (detail == null) {
                    WeLogger.i(TAG, "脚本运行时就绪：微信数据库可用（耗时 ${elapsed}ms）")
                } else {
                    WeLogger.e(TAG, "脚本运行时未就绪（自检 ${elapsed}ms）：$detail")
                }
            }, "WCX-Script-SelfCheck").apply { isDaemon = true }.start()
        }.onFailure {
            status = "引导异常：" + (it.message ?: it.javaClass.simpleName)
            WeLogger.e(TAG, "Hchat 脚本子系统安装失败", it)
        }
    }

    /**
     * 冷启动自愈看门狗：定时重排未装 Hook 任务；若多轮仍未推进到 WARMUP，则强制推进。
     * 目的：让「强停后/更新后重启」不再需要用户手动去脚本诊断里点修复。
     *
     * 关键：使用独立的 [HandlerThread] 而非主线程 Handler —— 否则每 5s 在主线程做 dex
     * 操作会直接卡微信 UI（用户反馈的「卡顿/假死」根因之一）。
     */
    private fun scheduleReadyWatchdog() {
        val watchdogThread = HandlerThread("WCX-Script-Watchdog").apply { start() }
        val handler = Handler(watchdogThread.looper)
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                attempts++
                val level = runCatching { DexInstallScheduler.readyLevelName() }.getOrDefault("")
                if (level.contains("WARMUP")) {
                    // 已就绪，主动退出线程，避免无谓占用资源
                    runCatching { watchdogThread.quitSafely() }
                    return
                }
                runCatching { DexInstallScheduler.retryAllPending() }
                    .onFailure { WeLogger.e(TAG, "看门狗重排失败", it) }
                if (attempts >= 2) {
                    runCatching { DexInstallScheduler.ensureWarmupReady() }
                        .onFailure { WeLogger.e(TAG, "看门狗强制推进失败", it) }
                }
                if (attempts < 6) handler.postDelayed(this, 8_000L)
                else runCatching { watchdogThread.quitSafely() }
            }
        }
        handler.postDelayed(task, 8_000L)
    }
}
