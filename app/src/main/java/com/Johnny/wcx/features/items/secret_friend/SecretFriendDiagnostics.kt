package com.Johnny.wcx.features.items.secret_friend

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger

/**
 * 密友功能诊断：把「哪一层没隐藏掉」变成可见信息。
 *
 * 密友由 ~25 个独立开关组成（会话 / 通讯录 / 搜索 / 聊天 / 资料 / 朋友圈 / 外围界面…），
 * 任一开关关闭，或某个依赖的类/方法在当前微信版本上失配，该面就会静默失效——用户只看到
 * 「还能搜到 / 还能点进去」，却无从判断是哪一层。本页集中展示总开关、名单、临时解除、
 * 每个隐藏面的开关状态，以及**关键锚点是否解析成功**（类/方法是否存在），
 * 据此可直接判断是「开关没开」还是「当前微信版本锚点漂移」。
 */
/** 密友总开关开启动作（供诊断行直接执行）。 */
private val OPEN_MASTER_ACTION: () -> String = {
    runCatching { Preferences.secretFriendMasterEnabled = true }
    "密友功能总开关已开启；若名单为空请到「密友名单管理」添加密友"
}

@Feature(
    name = "密友功能诊断",
    categories = ["密友功能"],
    description = "查看密友各隐藏面的实际生效状态：总开关、名单人数、临时解除、每个面的开关，以及关键锚点是否解析成功"
)
object SecretFriendDiagnostics : ClickableFeature() {

    private const val TAG = "SecretFriendDiagnostics"

    /** 诊断本身不需要开关，常驻可点。 */
    override val alwaysEnabled: Boolean = true

    /** 结构化诊断行（供诊断页渲染 + 点开详情）。 */
    data class DiagRow(
        val label: String,
        val section: String,
        val value: String,
        val ok: Boolean,
        val detail: String,
        val actionLabel: String? = null,
        val action: (() -> String)? = null,
    )

    override fun onClick(context: ComponentActivity) {
        val text = runCatching { buildReportText() }.getOrElse { error ->
            WeLogger.e(TAG, "build report failed", error)
            "诊断信息生成失败：" + (error.message ?: error.javaClass.simpleName)
        }
        runCatching {
            AlertDialog.Builder(context)
                .setTitle("密友功能诊断")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure { WeLogger.e(TAG, "show diagnostics failed", it) }
    }

    /** 结构化诊断行（label -> value），供密友诊断页面渲染（兼容入口）。 */
    fun collectRows(): List<Pair<String, String>> = collectDetailRows().map { it.label to it.value }

    /** 整段文本（供 ClickableFeature 的弹窗展示）。 */
    fun buildReportText(): String =
        collectRows().joinToString("\n") { it.first + "：" + it.second }

    /** 详细诊断行（含分组、是否正常、点开后的含义/恢复建议）。 */
    fun collectDetailRows(): List<DiagRow> {
        val rows = mutableListOf<DiagRow>()

        val masterOn = SecretFriendState.isSecretFriendMasterOn()
        val count = SecretFriendState.getStoredWxIds().size
        val tempShown = SecretFriendState.isTemporarilyShown()

        rows += DiagRow(
            label = "总开关（密友功能）", section = "总开关与名单",
            value = if (masterOn) "开启" else "关闭（所有隐藏已失效）",
            ok = masterOn,
            detail = "总开关 = 「密友功能」分类页顶部的开关，缺省开启。\n" +
                "关闭后本分类下所有隐藏/拦截都不生效，且名单保留、重开即恢复。\n" +
                "当前：${if (masterOn) "开启" else "关闭"}（存储键 ${Preferences.SECRET_FRIEND_MASTER_ENABLED}）。",
            actionLabel = if (masterOn) null else "开启总开关",
            action = if (masterOn) null else OPEN_MASTER_ACTION,
        )
        val listOk = count > 0
        rows += DiagRow(
            label = "名单人数", section = "总开关与名单",
            value = if (count == 0) "0（名单为空，全部隐藏不生效）" else "$count",
            ok = listOk,
            detail = "当前密友名单共 $count 人。\n名单为空时，即使开关都开着也不会有任何隐藏效果——请到「密友名单管理」里添加密友。",
        )
        rows += DiagRow(
            label = "临时解除", section = "总开关与名单",
            value = if (tempShown) "生效中（名单内所有人当前可见）" else "未激活",
            ok = !tempShown,
            detail = "临时解除期间，名单内所有人对你可见（用于临时查看）。\n当前：${if (tempShown) "生效中" else "未激活"}。到期后会自动恢复隐藏。",
        )

        rows += faceRow(masterOn, "主页会话隐藏", HideConversations.isEnabled)
        rows += faceRow(masterOn, "通讯录隐藏", HideSecretContacts.isEnabled)
        rows += faceRow(masterOn, "标签内隐藏", HideTagMembers.isEnabled)
        rows += faceRow(masterOn, "主页搜索隐藏", HideSearch.isEnabled)
        rows += faceRow(masterOn, "禁止进入聊天", BlockChat.isEnabled)
        rows += faceRow(masterOn, "禁止查看资料", BlockProfile.isEnabled)
        rows += faceRow(masterOn, "朋友圈隐藏", HideMoments.isEnabled)
        rows += faceRow(masterOn, "我的朋友圈隐藏", HideMyMoments.isEnabled)
        rows += faceRow(masterOn, "最近转发隐藏", HideRecentForward.isEnabled)
        rows += faceRow(masterOn, "状态页隐藏", HideStatusPage.isEnabled)
        rows += faceRow(masterOn, "存储空间记录隐藏", HideStorageRecords.isEnabled)
        rows += faceRow(masterOn, "存储空间缓存隐藏", HideStorageCache.isEnabled)
        rows += faceRow(masterOn, "免打扰（消息静音）", MuteSecretFriend.isEnabled)

        rows += probeClass("聊天页 ChattingUI", CHATTING_UI)
        rows += probeClass("资料页 ContactInfoUI", CONTACT_INFO_UI)
        rows += probeClass("通讯录 AddressLiveList", ADDRESS_LIVE_LIST)
        rows += probeClass("wcdb SQLiteDatabase", WCDB_DB)
        rows += probeClass("wcdb compat SQLiteDatabase", WCDB_DB_COMPAT)
        rows += probeClass("朋友圈评论 SnsCommentFooter", SNS_COMMENT_FOOTER)
        rows += probeClass("主界面 LauncherUI", LAUNCHER_UI)
        rows += probeMethod("ChattingUI.onCreate", CHATTING_UI, "onCreate")
        rows += probeMethod("ContactInfoUI.onCreate", CONTACT_INFO_UI, "onCreate")

        return rows
    }

    private fun faceRow(masterOn: Boolean, name: String, enabled: Boolean): DiagRow {
        val value = when {
            !masterOn -> "总开关关闭，不生效"
            enabled -> "开启"
            else -> "关闭（该层未隐藏）"
        }
        val detail = when {
            !masterOn -> "因为总开关关闭，本面当前不会隐藏任何内容。开启总开关后按本面自身开关生效。"
            enabled -> "本面已开启。若仍能看到该面的密友，多半是它依赖的类/方法在当前微信版本漂移（见「锚点自检」）。"
            else -> "本面自身开关关闭——到「密友功能」分类页打开它即可生效。"
        }
        return DiagRow("── $name ──", "各隐藏面", value, masterOn && enabled, detail)
    }

    private fun probeClass(name: String, className: String): DiagRow {
        val found = loadClass(className) != null
        return DiagRow(
            label = "── 锚点·$name ──", section = "锚点自检",
            value = if (found) "已找到" else "未找到（该面锚点失配）",
            ok = found,
            detail = "类 $className\n" + if (found) "在当前微信版本中存在。" else "未找到——依赖它的功能可能失效，需要适配当前微信版本。",
        )
    }

    private fun probeMethod(name: String, className: String, methodName: String): DiagRow {
        val result = runCatching {
            val clazz = loadClass(className) ?: return@runCatching false to "未找到类（该面无法拦截）"
            val found = clazz.declaredMethods.any { it.name == methodName } ||
                clazz.methods.any { it.name == methodName }
            found to if (found) "已找到" else "未找到方法（该面无法拦截）"
        }.getOrElse { false to ("探测异常：" + (it.message ?: it.javaClass.simpleName)) }
        return DiagRow(
            label = "── 锚点·$name ──", section = "锚点自检",
            value = result.second, ok = result.first,
            detail = "方法 $className.$methodName\n" + result.second,
        )
    }

    /** 用模块 ClassLoader 解析宿主类；解析不到（类不存在/CL 不可见）返回 null，不抛异常。 */
    private fun loadClass(className: String): Class<*>? = runCatching {
        Class.forName(className, false, SecretFriendDiagnostics::class.java.classLoader)
    }.getOrNull()

    private const val CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI"
    private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
    private const val ADDRESS_LIVE_LIST = "com.tencent.mm.ui.contact.address.AddressLiveList"
    private const val WCDB_DB = "com.tencent.wcdb.database.SQLiteDatabase"
    private const val SNS_COMMENT_FOOTER = "com.tencent.mm.plugin.sns.ui.SnsCommentFooter"
    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
    private const val WCDB_DB_COMPAT = "com.tencent.wcdb.compat.SQLiteDatabase"
}
