package com.wangzan101.mika.core.compare

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceCompareTest {
    private val screen = "当前 App:com.taobao.taobao\n特仑苏 250ml\n券后¥12.80\n原价¥20.00\n天猫超市"

    @Test fun emptyConfigUsesTheDefaultThreeAndACustomListReplacesThem() {
        assertEquals(listOf("com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.android"), PricePlatforms.parse("").platforms.map { it.packageName })
        assertFalse(PricePlatforms.parse("").fellBackToDefault)
        assertTrue(PricePlatforms.parse("不是包名").fellBackToDefault)
        val parsed = PricePlatforms.parse("抖音 com.ss.android.ugc.aweme\n坏掉的一行\n京东 com.jingdong.app.mall")
        assertEquals(listOf("抖音", "京东"), parsed.platforms.map { it.name })
        assertEquals(1, parsed.skipped)
        assertEquals(parsed.platforms, PricePlatforms.parse(PricePlatforms.format(parsed.platforms)).platforms)
    }

    @Test fun pricesAreTakenFromTheScreenQuoteAndSplitWhenBothAreVisible() {
        val d = PriceGrounding.decide(PriceCompareStatus.OK, "特仑苏 250ml 券后¥12.80 原价¥20.00 天猫超市", screen)
        assertTrue(d.store)
        assertEquals(PriceCompareStatus.OK, d.status)
        assertEquals("20.00", d.listPrice)
        assertEquals("12.80", d.payPrice)
        assertEquals("coupon", d.payKind)
        assertTrue(d.quoteOnScreen)
        assertEquals("250ml", PriceGrounding.groundedField("250ml", screen))
        assertEquals("", PriceGrounding.groundedField("券后¥9.90", screen))
        assertEquals("", PriceGrounding.groundedField("虚构商品", screen))
    }

    @Test fun aPriceThatIsNotOnTheScreenIsNotStored() {
        val d = PriceGrounding.decide(PriceCompareStatus.OK, "券后¥1.00", screen)
        assertFalse(d.store)
        assertEquals("quote_not_on_screen", d.reject)
        assertNull(d.payPrice)
        val login = PriceGrounding.decide(PriceCompareStatus.NEEDS_LOGIN, "请登录后查看", "别的页面")
        assertTrue(login.store)
        assertEquals(PriceCompareStatus.NEEDS_LOGIN, login.status)
        assertNull(login.payPrice)
        assertEquals("", login.evidence)
        val missing = PriceGrounding.decide(PriceCompareStatus.NOT_INSTALLED, "", null)
        assertTrue(missing.store)
        assertEquals(PriceCompareStatus.NOT_INSTALLED, missing.status)
        assertNull(missing.payPrice)
    }

    @Test fun okWithoutAReadablePriceIsUnreadable() {
        val d = PriceGrounding.decide(PriceCompareStatus.OK, "特仑苏 250ml", screen)
        assertTrue(d.store)
        assertEquals(PriceCompareStatus.UNREADABLE, d.status)
        assertNull(d.payPrice)
        assertNull(d.listPrice)
    }

    @Test fun theUserAnswerUsesRecordedRowsAndDropsInventedPrices() {
        val row = PriceRow(
            platform = "淘宝", packageName = "com.taobao.taobao", title = "特仑苏", spec = "250ml", shop = "天猫超市",
            status = PriceCompareStatus.OK, listPrice = "20.00", payPrice = "12.80", payKind = "coupon",
            evidence = "券后¥12.80", quoteOnScreen = true,
        )
        val shown = PriceCompareAnswer.present("请比价：特仑苏", "淘宝券后¥1.00，很便宜", listOf(row))
        assertFalse(shown.contains("1.00"))
        assertFalse(shown.contains("很便宜"))
        val view = PriceCompareAnswer.parse(shown)
        assertEquals("12.80", view.rows.single().payPrice)
        assertEquals("20.00", view.rows.single().listPrice)
        val explanation = PriceCompareAnswer.present("比一下特仑苏的价格", "三家都要登录，看到过¥9.90", emptyList())
        assertFalse(explanation.contains("9.90"))
        assertTrue(explanation.contains("三家都要登录"))
        assertEquals("250ml 还在", PriceCompareAnswer.stripPrices("250ml 还在"))
        assertEquals("普通回答", PriceCompareAnswer.present("帮我写一句问候", "普通回答", emptyList()))
    }

    @Test fun guardBlocksBuyingAndLoginButtons() {
        assertEquals("加入购物车", PriceCompareGuard.blockedTap("加入购物车"))
        assertEquals("领券", PriceCompareGuard.blockedTap("领券立减"))
        assertEquals("去登录", PriceCompareGuard.blockedTap("去登录"))
        assertNull(PriceCompareGuard.blockedTap("特仑苏 250ml"))
        assertNull(PriceCompareGuard.blockedTap("搜索"))
    }

    @Test fun probeLogNamesTheFieldsADeviceRunNeeds() {
        val row = PriceRow(
            platform = "京东", packageName = "com.jingdong.app.mall", status = PriceCompareStatus.LEFT_VIRTUAL_DISPLAY,
            quoteOnScreen = false, screenPackage = "com.jingdong.app.mall",
        )
        val line = PriceCompareLog.row(row)
        assertTrue(line.startsWith("PRICE_COMPARE "))
        assertTrue(line.contains("status=left_virtual_display"))
        assertTrue(line.contains("quote_on_screen=false"))
        assertTrue(line.contains("pay_kind=none"))
        val summary = PriceCompareLog.summary(listOf(row))
        assertTrue(summary.contains("left_virtual_display=1"))
        assertTrue(summary.contains("coupon=0"))
    }
}

class PriceCompareToolTest {
    @Test fun recordKeepsAPriceOnlyWhenTheQuoteIsOnTheLatestScreen() = runBlocking {
        val logs = mutableListOf<String>()
        val dispatcher = toolDispatcher(
            FakeScreen(),
            pricePlatforms = { listOf(PricePlatform("小店", "com.example.shop")) },
            probeLog = { logs += it },
        )
        val listed = dispatcher.execute(Part.ToolCall("p", "price_compare_platforms", "{}")).content
        assertEquals("com.example.shop", listed["platforms"]!!.jsonArray.single().jsonObject["package"]!!.jsonPrimitive.content)

        val invented = dispatcher.execute(Part.ToolCall("bad", "price_compare_record", """
            {"platform":"小店","package":"com.example.shop","status":"ok","quote":"券后¥1.00"}
        """.trim())).content
        assertEquals("quote_not_on_screen", invented["error"]!!.jsonPrimitive.content)
        assertTrue(logs.any { it.contains("rejected=quote_not_on_screen") })

        dispatcher.ctx.evidence.recentReads.addLast("小店牛奶 券后¥12.80 原价¥20 店铺甲")
        val saved = dispatcher.execute(Part.ToolCall("ok", "price_compare_record", """
            {"platform":"小店","package":"com.example.shop","status":"ok","quote":"券后¥12.80 原价¥20","title":"小店牛奶","shop":"店铺甲","note":"看到了登录价9.9"}
        """.trim())).content
        assertEquals("12.80", saved["pay_price"]!!.jsonPrimitive.content)
        assertEquals("20", saved["list_price"]!!.jsonPrimitive.content)
        assertEquals("coupon", saved["pay_kind"]!!.jsonPrimitive.content)
        val row = dispatcher.ctx.config.priceCompare.snapshot().single()
        assertEquals("小店牛奶", row.title)
        assertEquals("店铺甲", row.shop)
        assertEquals("", row.note)
        assertTrue(logs.any { it.contains("status=ok") && it.contains("pay=12.80") && it.contains("pay_kind=coupon") })
    }
}
