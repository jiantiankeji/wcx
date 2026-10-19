package h.Hchat.hooks.api.runtime;

import com.Johnny.wcx.features.api.core.WeDatabaseApi;

/**
 * WCX 侧数据库解析结果的桥接层。
 *
 * 背景：Hchat 的 {@link DexFinder} 在部分微信版本（如 8.0.79）上无法解析出
 * CoreStorage / SqliteDB wrapper，导致脚本数据库 API 与消息变更监听整体不可用，
 * 日志表现为「获取 CoreStorage 失败: 取库方法未返回对象」「消息变更监听未就绪: wrapperHooks=0」。
 *
 * WCX 侧 {@link WeDatabaseApi} 使用另一套 dex 特征，在同样版本上可用，
 * 因此这里把它作为兜底来源：Hchat 自己解析失败时才回落到这里，成功则维持原有链路。
 */
final class WcxDatabaseCompat {

    private WcxDatabaseCompat() {}

    /** CoreStorage 实例；不可用时返回 null。 */
    static Object coreStorage() {
        try {
            return WeDatabaseApi.hchatCoreStorage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 微信 SqliteDB wrapper 类；不可用时返回 null。 */
    static Class<?> sqliteDbWrapperClass() {
        try {
            return WeDatabaseApi.hchatSqliteDbWrapperClass();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
