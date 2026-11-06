package com.wangzan101.mika.core.agent

import org.junit.Assert.*
import org.junit.Test

class RelevantAppsTest {
    private val apps = listOf(
        "com.sankuai.meituan" to "美团", "com.ss.android.ugc.lifeservices" to "抖省省",
        "com.taobao.taobao" to "淘宝", "com.quark.browser" to "夸克",
        "com.google.android.apps.bard" to "Gemini", "com.bytedance.trae.cn" to "TRAE",
        "example.bank" to "银行", "custom.app" to "我的工具",
    )
    @Test fun shoppingFiltersAndIncludesInstalledChannels() {
        val result = RelevantApps.render("比较网吧四小时团购", apps)
        assertTrue(result.contains("抖省省")); assertTrue(result.contains("美团")); assertTrue(result.contains("淘宝"))
        assertFalse(result.contains("银行")); assertFalse(result.contains("TRAE"))
    }
    @Test fun icedCoffeeComparisonGetsShoppingCandidatesWithoutPrivateApps() {
        val result = RelevantApps.render("想喝冰美式 帮我看一下哪边便宜", apps)
        assertTrue(result.contains("美团")); assertTrue(result.contains("抖省省")); assertTrue(result.contains("淘宝"))
        assertFalse(result.contains("银行")); assertFalse(result.contains("TRAE"))
    }
    @Test fun researchAndCodingAreSeparate() {
        val search = RelevantApps.render("查询资料", apps)
        assertTrue(search.contains("Gemini")); assertTrue(search.contains("夸克")); assertFalse(search.contains("银行"))
        val code = RelevantApps.render("写代码", apps)
        assertTrue(code.contains("TRAE")); assertFalse(code.contains("淘宝"))
    }
    @Test fun articleWorkIncludesResearchAndCreationApps() {
        val candidates = apps + ("com.tencent.mp" to "公众号助手")
        val result = RelevantApps.render("开始写这个微信公众号文章", candidates)
        assertTrue(result.contains("公众号助手"))
        assertTrue(result.contains("Gemini"))
        assertTrue(result.contains("夸克"))
        assertFalse(result.contains("银行"))
        assertTrue(RelevantApps.render("给文章配图", candidates).contains("Gemini"))
    }
    @Test fun fiveCoreWorkflowsSuggestTheirDestinationApps() {
        val candidates = apps + listOf("com.twitter.android" to "X", "com.hpbr.bosszhipin" to "BOSS直聘", "com.ss.android.ugc.aweme" to "抖音", "com.tencent.mp" to "公众号助手")
        assertTrue(RelevantApps.render("刷 X", candidates).contains("com.twitter.android"))
        assertTrue(RelevantApps.render("找工作看职位", candidates).contains("BOSS直聘"))
        assertTrue(RelevantApps.render("比较外卖", candidates).contains("美团"))
        assertTrue(RelevantApps.render("进入直播间", candidates).contains("抖音"))
        assertTrue(RelevantApps.render("写公众号文章", candidates).contains("公众号助手"))
    }
    @Test fun explicitlyNamedAndUnknownTasksRetainFallback() {
        assertTrue(RelevantApps.render("打开我的工具", apps).contains("custom.app"))
        val result = RelevantApps.render("做一件事", apps)
        assertFalse(result.contains("example.bank")); assertTrue(result.contains("phone_list_apps"))
    }
    @Test fun userCategoriesOverrideSuggestionsAndPermitNewApps() {
        val overrides = mapOf("com.sankuai.meituan" to emptySet<String>(), "custom.app" to setOf("shopping and local deals"))
        val result = RelevantApps.render("网吧团购", apps, overrides)
        assertFalse(result.contains("美团")); assertTrue(result.contains("我的工具"))
        assertTrue(RelevantApps.render("打开美团看团购", apps, overrides).contains("美团"))
        assertTrue(result.contains("user-confirmed categories"))
    }
    @Test fun appNameSuggestionsMatchSameWayForDisplayAndPlanning() {
        val example = listOf("new.example" to "某某地图")
        assertTrue(RelevantApps.suggestedCategories("new.example", "某某地图").contains("maps"))
        assertTrue(RelevantApps.render("查询地图", example).contains("某某地图"))
    }
}
