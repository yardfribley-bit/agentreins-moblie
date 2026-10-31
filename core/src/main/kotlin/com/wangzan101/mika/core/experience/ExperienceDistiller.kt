package com.wangzan101.mika.core.experience

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.memory.Tokenize
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.ActionDigest
import com.wangzan101.mika.core.skills.SkillRedactor
import com.wangzan101.mika.core.tool.ToolIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把一次运行收成可重用的做法。步骤只用屏上的名字,输入的字、金额和密码不进记录。
 * 不另调模型:路线回放和技能草稿都拒掉的任务(付款、步骤多、用户确认办成)也能留下。
 */
object ExperienceDistiller {
    private val FORCE = Regex("记住这个做法|记住做法|以后都这样做|以后都按这个|remember this way|remember how", RegexOption.IGNORE_CASE)
    private val COORDS = Regex("^\\d+\\s*[,，]\\s*\\d+$")
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun wantsRemember(utterances: List<String>): Boolean = utterances.any { FORCE.containsMatchIn(it) }

    fun distill(goal: String, events: List<RunEvent>, tools: ToolIndex, utterances: List<String> = emptyList()): ExperienceDraft? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val confirmed = events.any { it is RunEvent.Takeover && it.outcome in CONFIRMED }
        val succeeded = ended?.outcome == "succeeded" || confirmed
        val forced = wantsRemember(utterances)
        val lines = ActionDigest.of(events, tools)
        val named = argsByStep(events)
        val steps = ArrayList<ExperienceStep>()
        val avoid = ArrayList<String>()
        var appPackage: String? = null
        var appLabel: String? = null
        for (line in lines) {
            val where = line.where?.let(::clean)
            if (appPackage == null && where != null && PACKAGE.matches(where)) appPackage = where
            if (appLabel == null && where != null && !PACKAGE.matches(where)) appLabel = where
            val target = clean(line.target ?: named[line.step])
            if (line.tool.contains("launch") && target != null && PACKAGE.matches(target)) appPackage = target
            val failed = line.error != null || line.changed == false
            if (failed) {
                describe(line.tool, target, line.error)?.let { avoid += it }
            } else if (target != null || line.tool.contains("launch")) {
                steps += ExperienceStep(line.tool, target, where)
            }
        }
        if (appPackage == null) appPackage = events.filterIsInstance<RunEvent.Observed>()
            .mapNotNull { it.place?.takeIf { p -> p.kind == PlaceKind.App }?.name }
            .firstOrNull { PACKAGE.matches(it) }
        val intent = clean(Redactor.text(goal)) ?: return null
        if (SensitiveFilter.hit(intent) != null) return null
        val keywords = (Tokenize.terms(intent) + listOfNotNull(appLabel, appPackage)).filter { it.length >= 2 }.distinct().take(8)
        val pitfalls = events.filterIsInstance<RunEvent.Barrier>().map { "barrier ${it.kind}" }.distinct().take(3)
        val preconditions = listOfNotNull(appPackage?.let { pkg ->
            "Start in ${appLabel ?: pkg} ($pkg), or launch it before the first tap."
        })
        val keep = (succeeded || forced) && steps.isNotEmpty()
        if (!keep && avoid.isEmpty() && pitfalls.isEmpty()) return null
        return ExperienceDraft(intent, keywords, appPackage, appLabel, steps.take(MAX_STEPS), preconditions, pitfalls, avoid.distinct().take(6), keep)
    }

    private fun describe(tool: String, target: String?, error: String?): String? {
        val what = listOfNotNull(tool, target?.let { "「$it」" }).joinToString(" ")
        val why = error?.let { " — $it" }.orEmpty()
        return clean("$what$why")
    }

    private fun clean(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (COORDS.matches(text)) return null
        if (SensitiveFilter.hit(text) != null) return null
        val scrubbed = SkillRedactor.scrubNumbers(text, emptySet()).replace(SkillRedactor.PLACEHOLDER, "<参数>")
        return scrubbed.trim().take(80).takeIf { it.isNotEmpty() && SensitiveFilter.hit(it) == null }
    }

    private fun argsByStep(events: List<RunEvent>): Map<Int, String?> {
        val out = HashMap<Int, String?>()
        for (e in events) {
            if (e !is RunEvent.ModelReply || e.argsJson == null) continue
            val o = runCatching { Json.parseToJsonElement(e.argsJson) as? JsonObject }.getOrNull() ?: continue
            val target = (o["target"] as? JsonPrimitive)?.contentOrNull
            if (target != null) out[e.step] = target
        }
        return out
    }

    private val CONFIRMED = setOf("complete", "user_finished")
    private const val MAX_STEPS = 12
}

/** 开局注入的推荐计划。超预算就少放几步。 */
object ExperienceContext {
    const val MAX_CHARS = 900

    fun render(items: List<Experience>): String? {
        val plans = items.filter { it.confidence > 0 && it.steps.isNotEmpty() }
        if (plans.isEmpty()) return null
        val body = plans.take(2).joinToString("\n\n") { renderOne(it) }
        val text = HEADER + "\n" + body
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1) + "…"
    }

    private fun renderOne(e: Experience): String = buildString {
        val app = listOfNotNull(e.appLabel, e.appPackage?.let { "($it)" }).joinToString(" ")
        append("Intent: ").append(e.intent)
        if (app.isNotBlank()) append("\nApp: ").append(app)
        append("\nConfidence: ").append(e.confidence).append(" (").append(e.successes).append(" success")
        if (e.failures > 0) append(", ").append(e.failures).append(" failure")
        append(")")
        e.preconditions.forEach { append("\nPrecondition: ").append(it) }
        append("\nSteps:")
        e.steps.forEachIndexed { i, s ->
            append("\n").append(i + 1).append(". ").append(s.action)
            s.target?.let { append(" 「").append(it).append("」") }
            s.where?.let { append(" @ ").append(it) }
        }
        if (e.avoid.isNotEmpty()) {
            append("\nDo not repeat these failed attempts:")
            e.avoid.take(4).forEach { append("\n- ").append(it) }
        }
        e.pitfalls.forEach { append("\nPitfall: ").append(it) }
    }

    private const val HEADER =
        "Recommended plan from an earlier success of a similar task. Follow these steps first, matching each one by the words on the screen. If a label is not on the screen, stop following and explore. A fresh screen wins over this plan."
}
