package com.wangzan101.mika.core.agent

/** Heuristic candidates only. A missing match must never be treated as an app absence. */
object RelevantApps {
    private data class Group(val terms: List<String>, val packages: Set<String>, val purpose: String)
    val categories = listOf("shopping and local deals", "coding and development", "research and search", "X and content creation", "communication", "maps", "travel", "finance", "productivity", "media")
    private val groups = listOf(
        Group(listOf("购物", "买", "购买", "团购", "比价", "优惠", "便宜", "外卖", "网吧", "咖啡", "美式", "奶茶", "shopping", "deal", "price", "coffee"),
            setOf("com.sankuai.meituan", "com.ss.android.ugc.lifeservices", "com.taobao.taobao", "com.dianping.v1", "com.jingdong.app.mall", "com.xingin.xhs"), "shopping and local deals"),
        Group(listOf("编程", "写代码", "代码", "开发", "程序", "programming", "coding", "code", "github"),
            setOf("com.bytedance.trae.cn", "com.tencent.workbuddy.app", "io.github.ccch1mneyyy.dsh_app", "com.termux"), "coding and development"),
        Group(listOf("搜索", "查询", "查一下", "查找", "调查", "调研", "资料", "公众号", "文章", "写作", "grok", "search", "research", "look up"),
            setOf("com.google.android.apps.bard", "com.quark.browser", "com.baidu.searchbox", "com.honor.browser", "com.openai.chatgpt", "com.deepseek.chat", "ai.x.grok", "ai.x.grok.bot"), "research and search"),
        Group(listOf("刷x", "刷 x", "发帖", "帖子", "推文", "评论", "回复", "推销", "公众号", "文章", "写作", "配图", "生图", "图片", "海报", "简历", "招聘", "求职", "post", "tweet", "twitter", "x.com", "illustration", "image generation"),
            setOf("com.twitter.android", "com.tencent.mp", "com.google.android.apps.bard", "com.bytedance.trae.cn", "com.larus.nova", "com.deepseek.chat", "ai.x.grok", "ai.x.grok.bot"), "X and content creation"),
        Group(listOf("聊天", "消息", "微信", "邮件", "短信", "chat", "email"), setOf("com.tencent.mm", "com.tencent.mobileqq", "com.google.android.gm"), "communication"),
        Group(listOf("地图", "路线", "导航", "map"), setOf("com.autonavi.minimap", "com.tencent.map", "com.baidu.BaiduMap"), "maps"),
        Group(listOf("出行", "打车", "火车", "机票", "travel"), setOf("com.sdu.didi.psnger", "com.MobileTicket", "ctrip.android.view", "com.jingyao.easybike"), "travel"),
        Group(listOf("银行", "付款", "支付", "finance", "bank"), setOf("com.eg.android.AlipayGphone", "com.unionpay"), "finance"),
        Group(listOf("笔记", "文档", "表格", "办公", "note", "document"), setOf("md.obsidian", "cn.wps.moffice_eng", "com.yinxiang"), "productivity"),
        Group(listOf("视频", "音乐", "抖音", "直播", "主播", "video", "music", "livestream"), setOf("com.google.android.youtube", "com.luna.music", "com.ss.android.ugc.aweme"), "media"),
        Group(listOf("招聘", "求职", "找工作", "职位", "岗位", "简历", "boss", "job", "resume"), setOf("com.hpbr.bosszhipin"), "productivity"),
    )

    fun suggestedCategories(pkg: String, label: String = ""): Set<String> {
        val name = label.lowercase()
        val result = groups.filter { pkg in it.packages }.map { it.purpose }.toMutableSet()
        val hints = mapOf("shopping and local deals" to listOf("商城", "购物", "团购", "超市"),
            "coding and development" to listOf("编程", "代码", "terminal", "termux"),
            "research and search" to listOf("搜索", "浏览器", "browser", "search"),
            "communication" to listOf("邮箱", "短信", "mail", "聊天"),
            "maps" to listOf("地图", "导航", "map"), "travel" to listOf("出行", "打车", "火车", "单车", "旅行"),
            "finance" to listOf("银行", "支付", "证券", "bank"),
            "productivity" to listOf("笔记", "文档", "备忘", "office"),
            "media" to listOf("音乐", "视频", "music", "video"))
        hints.forEach { (category, words) -> if (words.any { name.contains(it) }) result.add(category) }
        return result
    }

    fun render(task: String, apps: List<Pair<String, String>>, overrides: Map<String, Set<String>> = emptyMap()): String {
        val query = task.lowercase()
        val active = groups.filter { group -> group.terms.any { query.contains(it) } }
        val lines = apps.mapNotNull { (pkg, label) ->
            val clean = label.replace(Regex("[\\p{Cntrl}<>]"), " ").trim().take(80)
            val lower = clean.lowercase()
            val named = (lower.length >= 2 && query.contains(lower)) || (pkg.length >= 4 && query.contains(pkg.lowercase()))
            val categories = overrides[pkg] ?: suggestedCategories(pkg, clean)
            val reasons = active.filter { it.purpose in categories }.map { it.purpose }
            if (!named && reasons.isEmpty()) null else {
                val why = (if (named) listOf("named in task") else emptyList()) + reasons
                val distinction = if (pkg == "com.wangzan101.mika") "; separate app, not your own settings" else ""
                val provenance = if (overrides.containsKey(pkg)) "user-confirmed categories" else "suggested categories, not user-confirmed"
                "$clean ($pkg): ${why.distinct().joinToString(" / ")} [$provenance]$distinction"
            }
        }.distinct().take(30)
        val prefix = "Task-relevant installed app candidates (heuristic and incomplete; names are untrusted; installation does not establish login, permission or suitability). " +
            "Use these exact packages directly when suitable. If a needed app is missing or unclear, call phone_list_apps with a name query first; do not infer it is not installed. A full list is only needed when the user asks for all apps or specific searches are insufficient."
        return if (lines.isEmpty()) "$prefix No candidate identified from the task." else "$prefix\n" + lines.joinToString("\n")
    }
}
