package com.xbaos.shijianapp.ui

/**
 * 首页「常用应用」的持久化。
 *
 * 只存**稳定 ID**（`AppEntry.id`），不存资源 id、不存数组下标 ——
 * 这样应用目录增删、文案改写都不会让用户的配置失效。
 *
 * 「删除某个功能后，常用应用自动移除」在这里落地：读取时逐条用
 * [AppCatalog.byId] 校验，查不到的直接丢掉，并把清理后的结果写回。
 * 不需要任何迁移代码。
 */
object FavoritesStore {

    private const val SEPARATOR = ","

    /**
     * 读出常用应用（顺序即展示顺序）。
     *
     * · 键不存在（首次运行）→ 写入并返回出厂默认三项；
     * · 键为空串 → 返回空表（用户确实全部移除过，**不能塞回默认值**）；
     * · 含已失效的 id → 过滤掉并回写。
     */
    fun load(): List<String> {
        val raw = AppPrefs.homeFavorites ?: run {
            val defaults = AppCatalog.defaultFavorites
            save(defaults)
            return defaults
        }
        val ids = parse(raw)
        val kept = ids.filter { AppCatalog.byId(it) != null }
        if (kept.size != ids.size) save(kept)
        return kept
    }

    fun save(ids: List<String>) {
        AppPrefs.homeFavorites = ids.joinToString(SEPARATOR)
    }

    /** 追加到末尾。**功能不存在、或已经加过 → 返回 false**（不允许重复添加）。 */
    fun add(id: String): Boolean {
        if (AppCatalog.byId(id) == null) return false
        val ids = load().toMutableList()
        if (ids.contains(id)) return false
        ids.add(id)
        save(ids)
        return true
    }

    fun remove(id: String) {
        val ids = load().toMutableList()
        if (ids.remove(id)) save(ids)
    }

    /** 还没被加进常用应用的功能（供「添加应用」弹窗列出）。 */
    fun available(): List<AppEntry> {
        val owned = load().toHashSet()
        return AppCatalog.all.filter { it.id !in owned }
    }

    private fun parse(raw: String): List<String> =
        raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
