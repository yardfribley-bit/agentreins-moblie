package com.wangzan101.mika.core.compare

/** 比价要查的一家购物 App。名单可改，默认三家只是没另配时的起点。 */
data class PricePlatform(val name: String, val packageName: String)

object PricePlatforms {
    val DEFAULT: List<PricePlatform> = listOf(
        PricePlatform("淘宝", "com.taobao.taobao"),
        PricePlatform("京东", "com.jingdong.app.mall"),
        PricePlatform("拼多多", "com.xunmeng.android"),
    )

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    data class Parsed(val platforms: List<PricePlatform>, val skipped: Int, val fellBackToDefault: Boolean = false)

    /**
     * 一行一个「显示名 包名」，也认「显示名|包名」。
     * 空文本用 [DEFAULT]。有能用的行就只用那些行；一行都认不出时才退回默认，并数出跳过的行。
     */
    fun parse(text: String): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return Parsed(DEFAULT, 0)
        var skipped = 0
        val out = mutableListOf<PricePlatform>()
        for (line in lines) {
            val parts = line.split(Regex("\\s+|\\|"), limit = 2).map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            val pkg = parts.getOrNull(1).orEmpty()
            if (parts.size < 2 || name.isEmpty() || name.length > 20 || !PACKAGE.matches(pkg) || out.any { it.packageName == pkg }) {
                skipped++
                continue
            }
            out += PricePlatform(name, pkg)
        }
        if (out.isEmpty()) return Parsed(DEFAULT, skipped, fellBackToDefault = true)
        return Parsed(out, skipped)
    }

    fun format(list: List<PricePlatform>): String = list.joinToString("\n") { "${it.name} ${it.packageName}" }
}
