package com.xbaos.shijianapp.appsys

/**
 * 桌面小组件里「按尺寸折算字号」的公共工具。
 *
 * RemoteViews 不支持 autosize，也没有布局权重，更拿不到文本的测量结果 ——
 * 只能按「字符宽度估算」把 dp 换算成 sp 再推给宿主。
 * 秒表组件与纪念日组件共用这一套，避免两边各估各的、同一句文案在两张卡上宽窄不一。
 */
object WidgetText {

    /**
     * 行高估算：includeFontPadding=false 时行框 ≈ ascent + descent，多数无衬线体约 1.15em。
     *
     * ⚠ 它是**偏小**的估算（Noto Sans CJK 实际约 1.2em），
     *   所以调用方在拿它做高度预算时要自己留一档余量，否则会「刚好填满 → 溢出裁切」。
     */
    const val LINE_HEIGHT_EM = 1.15f

    /**
     * 宽度方向的安全系数（与秒表组件的高度安全系数同一思路）。
     *
     * [emWidth] 的系数是经验值，实测对「还有 97 天」这类混排文案偏乐观约 5%——
     * 按估算值恰好填满的字号在真机上会顶到 `ellipsize` 那条线。
     * 统一收 4%：宁可字号保守一点，也不要出现「还有 …」。
     */
    const val WIDTH_SAFETY = 0.94f

    /** 宽度估算系数：等宽数字每个约占 0.62em，CJK 方块字 1em，拉丁字母均宽约 0.52em。 */
    private const val DIGIT_ADVANCE_EM = 0.62f
    private const val CJK_ADVANCE_EM = 1f
    private const val LATIN_ADVANCE_EM = 0.52f

    /** CJK / 全角字符的码位下界（覆盖中日韩统一表意文字与兼容区，够本项目用了）。 */
    private const val CJK_START = 0x2E80
    private const val CJK_END = 0x9FFF

    /**
     * 估算一段文字的宽度，单位是「自己的字号」（em）。
     *
     * ⚠ 数字必须单独按 [DIGIT_ADVANCE_EM] 算：本项目几个数字视图都写了
     *   `fontFamily="monospace"`，等宽数字比拉丁字母的平均宽度大一截。
     *   按拉丁字母估会偏小，反推出来的字号就偏大，两位数（10 个秒表以上）直接出血。
     */
    fun emWidth(text: String): Float {
        var em = 0f
        for (ch in text) {
            em += when {
                ch.code in CJK_START..CJK_END -> CJK_ADVANCE_EM
                ch in '0'..'9' -> DIGIT_ADVANCE_EM
                else -> LATIN_ADVANCE_EM
            }
        }
        // 空串时给一个非零值，免得调用方除以零拿到 Infinity 字号
        return em.coerceAtLeast(0.01f)
    }

    /**
     * 一行文字在 [widthDp] 宽度里放得下时，字号最大能到多少（sp）。
     *
     * 结果被夹在 [minSp]..[maxSp] 之间：下限保证再窄也不至于小到看不清，
     * 上限保证短文案（比如「就是今天」）不会因为没有宽度压力而撑成巨无霸。
     */
    fun widthFitSp(text: String, widthDp: Float, minSp: Float, maxSp: Float): Float =
        (widthDp / emWidth(text)).coerceIn(minSp, maxSp)
}
