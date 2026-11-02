package com.wangzan101.mika.core.memory

/** 用户点名要记住，以及账号档案里能拿来检索的邮箱和平台。 */
object AccountCues {
    private val REMEMBER = Regex("记住|记一下|remember", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val PLATFORMS = listOf("github", "google", "gmail", "twitter", "quark", "夸克", "抖音", "微信", "boss")

    fun wantsRemember(text: String) = REMEMBER.containsMatchIn(text)

    fun keywords(content: String): List<String> {
        val lower = content.lowercase()
        return (EMAIL.findAll(content).map { it.value.lowercase() }.toList() + PLATFORMS.filter { it in lower }).distinct().take(4)
    }

    /** 用户说了记住之后，多出来的平台名、邮箱片段可以记；电话、门牌、地址不行。 */
    fun allowedExtra(spec: String, content: String): Boolean {
        val s = spec.lowercase()
        if (PLATFORMS.any { it == s }) return true
        return EMAIL.findAll(content).any { s in it.value.lowercase() }
    }
}
