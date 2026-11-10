package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.compare.PriceCompareLog
import com.wangzan101.mika.core.compare.PriceCompareStatus
import com.wangzan101.mika.core.compare.PriceGrounding
import com.wangzan101.mika.core.compare.PriceRow
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.INTERFACES
import com.wangzan101.mika.core.uistate.screenText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PriceComparePlatformsTool : com.wangzan101.mika.core.tool.AgentTool {
    override val verb = Verb.Observe
    override val risk = Risk.Read
    override val label = "比价平台"
    override val def = ToolDef(
        "price_compare_platforms",
        Prompts.tool("price_compare_platforms"),
        emptyMap(),
        emptyList(),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        ctx.config.priceCompare.arm()
        val list = ctx.config.pricePlatforms()
        return ok {
            put("platforms", buildJsonArray {
                list.forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("package", p.packageName)
                    })
                }
            })
        }
    }
}

internal object PriceCompareRecordTool : com.wangzan101.mika.core.tool.AgentTool {
    override val verb = Verb.Observe
    override val risk = Risk.Read
    override val label = "记下比价"
    override val def = ToolDef(
        "price_compare_record",
        Prompts.tool("price_compare_record"),
        mapOf(
            "platform" to str("Store name shown to the user, such as the configured display name."),
            "package" to str("Android package name of that store."),
            "status" to str(
                "What you found: ok, not_installed, needs_login, out_of_stock, left_virtual_display, not_found, or unreadable.",
                PriceCompareStatus.ALL.toList(),
            ),
            "quote" to str("Short verbatim excerpt from the latest screen read. Required when a price is visible. Omit when there is nothing to quote."),
            "title" to str("Product name copied from the screen. Omit if you did not read one."),
            "spec" to str("Spec copied from the screen, such as size. Omit if you did not read one."),
            "shop" to str("Shop name copied from the screen. Omit if you did not read one."),
            "note" to str("Short reason with no digits, such as which login wall appeared. Omit if you have nothing to add."),
        ),
        listOf("platform", "package", "status"),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        ctx.config.priceCompare.arm()
        val platform = a.str("platform", max = 20)!!
        val pkg = a.str("package", max = 120)!!
        if (!PACKAGE_NAME_RE.matches(pkg)) return fail("invalid_arguments", "package: must be an Android package name")
        val status = a.str("status")!!
        if (status !in PriceCompareStatus.ALL) return fail("invalid_arguments", "status: unknown")
        val quote = a.str("quote", max = 400).orEmpty()
        val screen = ctx.priceScreenText()
        val decision = PriceGrounding.decide(status, quote, screen)
        if (!decision.store) {
            ctx.config.probeLog(
                "PRICE_COMPARE platform=${platform.replace(Regex("\\s+"), "_")} package=$pkg status=$status quote_on_screen=false rejected=quote_not_on_screen",
            )
            return fail(
                "quote_not_on_screen",
                "quote is not an excerpt of the latest screen text. Copy a short verbatim line from the last screen read and call again. No price was saved.",
            )
        }
        val evidence = decision.evidence
        val note = a.str("note", max = 40).orEmpty().trim().takeIf { it.none(Char::isDigit) }.orEmpty()
        val row = PriceRow(
            platform = platform.trim(),
            packageName = pkg,
            title = PriceGrounding.groundedField(a.str("title", max = 200).orEmpty(), screen),
            spec = PriceGrounding.groundedField(a.str("spec", max = 200).orEmpty(), screen),
            shop = PriceGrounding.groundedField(a.str("shop", max = 200).orEmpty(), screen),
            status = decision.status,
            listPrice = decision.listPrice,
            payPrice = decision.payPrice,
            payKind = decision.payKind,
            evidence = evidence,
            quoteOnScreen = decision.quoteOnScreen,
            screenPackage = ctx.priceScreenPackage(),
            note = note,
        )
        ctx.config.priceCompare.upsert(row)
        ctx.config.probeLog(PriceCompareLog.row(row))
        return ok {
            put("platform", row.platform)
            put("package", row.packageName)
            put("status", row.status)
            put("quote_on_screen", row.quoteOnScreen)
            put("pay_kind", row.payKind)
            row.listPrice?.let { put("list_price", it) }
            row.payPrice?.let { put("pay_price", it) }
            put("saved", JsonPrimitive(true))
        }
    }
}

internal fun ToolContext.priceScreenText(): String {
    val tree = this[INTERFACES]?.sessions?.get("phone")?.lastSnapshot?.let(::screenText).orEmpty()
    val reads = evidence.recentReads.joinToString("\n")
    return listOf(tree, reads).filter { it.isNotBlank() }.joinToString("\n")
}

internal fun ToolContext.priceScreenPackage(): String =
    this[INTERFACES]?.sessions?.get("phone")?.lastSnapshot?.packageName.orEmpty()
