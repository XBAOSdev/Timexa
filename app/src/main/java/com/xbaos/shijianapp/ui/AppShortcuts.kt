package com.xbaos.shijianapp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xbaos.shijianapp.R

/**
 * 「把某个功能钉到系统桌面」这件事的唯一出口。
 *
 * ## 为什么是「固定快捷方式（pinned shortcut）」而不是普通快捷方式
 *
 * 本项目已有的 `@xml/shortcuts` 是**静态快捷方式**：长按应用图标弹出来的那一小列，
 * 条目固定、用户不能改。而这里要做的是「用户自己挑一个功能，让它像独立应用一样
 * 躺在桌面上」—— 那在 Android 里只有 pinned shortcut 一条路（`requestPinShortcut`，API 26+）。
 *
 * ## 「删掉某个功能，桌面上的快捷方式自动消失」怎么做到
 *
 * 快捷方式是**用户创建的**，应用无权直接删（`removeLongLivedShortcuts` 只对动态快捷方式有效）。
 * 但应用**可以禁用它** —— `disableShortcuts`，桌面收到后会把它移除或置灰。
 * 于是只需要在启动时做一次对账：[reconcile] 把「固定在桌面、但 id 已不在
 * [AppCatalog] 里」的那些全部禁掉。
 *
 * 要不要写迁移代码？**不要。** 判据就是 [AppCatalog.byId] 返回 null，
 * 与「常用应用」用的是同一套逻辑 —— 功能目录是唯一事实来源，读的一方尊重 null 就行。
 *
 * ⚠ 快捷方式 id 用 `app_` 前缀：系统给的列表里可能混有别的来源，
 *   没有前缀的一律不碰（我们既不了解也管不着）。
 */
object AppShortcuts {

    /** 本应用创建的快捷方式 id 前缀。**不要改** —— 改了等于把用户已固定的全部作废。 */
    private const val ID_PREFIX = "app_"

    /** 条目 → 快捷方式 id。 */
    private fun shortcutId(entry: AppEntry): String = ID_PREFIX + entry.id

    /**
     * 当前桌面是否支持「应用自己请求固定快捷方式」。
     *
     * 由 Launcher 实现决定，**不是所有桌面都支持**（部分定制 ROM 的桌面就不支持）。
     * 返回 false 时调用方必须给出「手动添加」的说明，不能让按钮点了没反应。
     */
    fun isSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * 请求把 [entry] 固定到桌面。
     *
     * 直接调用，不弹我们自己的二次确认 —— 系统会弹它自己的确认框
     * （「要将 xx 添加到主屏幕吗？」），再叠一层确认纯属骚扰。
     *
     * 这里**刻意不传 resultCallback**：它要求一个 `PendingIntent` 去跨进程听
     * 「已添加」的广播，而用户到底点没点「添加」由桌面决定 ——
     * 列表状态每次 `onResume` 重查一遍 [pinnedIds] 就够了，不必为此增加一条广播通道。
     *
     * @return false 表示桌面不支持 / 系统拒绝了请求，调用方应转为手动指引。
     */
    fun requestPin(activity: Activity, entry: AppEntry): Boolean {
        if (!isSupported(activity)) return false
        val intent = AppCardBuilder.launchIntent(activity, entry) ?: return false
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(activity, buildInfo(activity, entry, intent), null)
        }.getOrDefault(false)
    }

    /**
     * 组装 `ShortcutInfoCompat`。
     *
     * 单独一个方法是因为 `setIntent` 有一个硬要求：Intent **必须带 action**，
     * 否则构造时就抛 `IllegalArgumentException`。这条约束只在这里表达一次，
     * 免得以后新增调用点时忘了加 —— 那是运行期崩溃，编译期查不出来。
     */
    private fun buildInfo(activity: Activity, entry: AppEntry, intent: Intent): ShortcutInfoCompat {
        val label = activity.getString(entry.nameRes)
        return ShortcutInfoCompat.Builder(activity, shortcutId(entry))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(activity, entry.iconRes))
            // 桌面是从**别的进程**发起启动的，没有 action 的 Intent 会被系统直接拒绝
            .setIntent(intent.setAction(Intent.ACTION_VIEW))
            .build()
    }

    /**
     * 当前**真的固定在桌面上**的功能 id 集合。
     *
     * 用作列表里「已添加 / 添加」的状态来源 —— 用户也可能在桌面上把快捷方式删掉，
     * 那时这里读到的才是真相（所以每次 `onResume` 都要重查，不能只在进页面时查一次）。
     *
     * API 26 以下没有这套 API，返回空集合（那时也固定不了，添加入口本来就不会出现）。
     */
    fun pinnedIds(context: Context): Set<String> {
        if (shortcutManager(context) == null) return emptySet()
        return pinnedShortcuts(context).mapTo(HashSet()) { entryId(it.id) }
    }

    /**
     * 启动时对账：桌面上还留着、但功能已经从目录里删掉的快捷方式，一律禁用。
     *
     * 放在 `ShijianApp.onCreate` 里跑。代价是每次冷启动读一次快捷方式列表
     * （很短、没有任何 IO），换来的是「删功能」这件事**不需要记得额外做什么**。
     *
     * ## 为什么是「禁用」而不是「删除」
     *
     * 平台**没有**给应用任何删除用户固定快捷方式的能力，`ShortcutManager` 上只有三个移除方法：
     *   · `removeDynamicShortcuts` —— 只管动态快捷方式；
     *   · `removeAllDynamicShortcuts` —— 同上；
     *   · `removeLongLivedShortcuts` —— 只管**长时效**快捷方式（`setLongLived(true)`，API 30+）。
     * 而「禁用」恰恰是官方指定的正解：`ShortcutManager.disableShortcuts` 的文档原文就是
     * 「Disable **pinned** shortcuts, showing the user a custom error message when they try to
     * select the disabled shortcuts」—— 专门为「目标已经不存在了」这种情形准备的，
     * 用户点它时会看到我们给的那句话（[R.string.widget_center_shortcut_removed]）。
     *
     * ⚠ 那为什么不干脆改用 `setLongLived(true)` + `removeLongLivedShortcuts` 把它删干净？
     *   长时效的真实语义是「**卸载应用后这条快捷方式仍然留在桌面上**」——
     *   为了一个几乎不会发生的场景（功能被删）去换一个很常见的场景（用户卸载应用后
     *   桌面上躺着一个点不动的死图标），是明显的亏本买卖。所以不改。
     *   实测（Pixel Launcher / API 35）：禁用后图标会留在桌面上，点它弹出提示语而不启动 ——
     *   这正是平台允许的最好结果。
     *
     * ## 反向对账同样是必须的
     *
     * 只做「禁用」会留下一个坑：万一那个功能后来又回来了（或用户重新导入了数据），
     * 快捷方式仍是禁用态，桌面上就出现了一条**功能明明存在、却点不动**的快捷方式。
     * 所以这里两头都查：解析不到的禁用，解析得到的启用。
     */
    fun reconcile(context: Context) {
        if (shortcutManager(context) == null) return
        val pinned = pinnedShortcuts(context)
        if (pinned.isEmpty()) return

        // 两个方向各自独立 try：禁用因故失败时，不该连带把「该启用的」也一起跳过。
        val (stale, alive) = pinned.partition { AppCatalog.byId(entryId(it.id)) == null }
        if (stale.isNotEmpty()) {
            runCatching {
                ShortcutManagerCompat.disableShortcuts(
                    context,
                    stale.map { it.id },
                    context.getString(R.string.widget_center_shortcut_removed)
                )
            }
        }
        if (alive.isNotEmpty()) {
            runCatching {
                // ⚠ 这个重载收的是 ShortcutInfoCompat 列表，**不是 id 列表** ——
                //   与 disableShortcuts 不对称，是 androidx 里少见的坑，照着 disable 写必编译不过。
                ShortcutManagerCompat.enableShortcuts(context, alive)
            }
        }
    }

    /** 读一次桌面上的固定快捷方式，只留下本应用创建的。 */
    private fun pinnedShortcuts(context: Context): List<ShortcutInfoCompat> = runCatching {
        // ⚠ 这个重载收的是 Context（不是 ShortcutManager）——
        //   androidx 内部要靠它去取 LauncherApps 服务，传 manager 会直接编译不过。
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .filter { it.id.startsWith(ID_PREFIX) }
    }.getOrDefault(emptyList())

    /** 快捷方式 id → 功能 id。 */
    private fun entryId(shortcutId: String): String = shortcutId.removePrefix(ID_PREFIX)

    /** 拿到 ShortcutManager；API 26 以下没有这个服务，返回 null。 */
    private fun shortcutManager(context: Context): ShortcutManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return context.getSystemService(ShortcutManager::class.java)
    }
}
