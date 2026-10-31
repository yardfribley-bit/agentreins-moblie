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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把一次运行收成可重用的做法。步骤用界面树上的文字或内容描述,不用模型自己改写的说法。
 * 输入的字留下,密码、金额和聊天名称换成占位。成功只认任务结局是 succeeded。
 * 不另调模型:路线回放和技能草稿都拒掉的任务也能留下。
 */
object ExperienceDistiller {
    private val FORCE = Regex("记住这个做法|记住做法|以后都这样做|以后都按这个|remember this way|remember how", RegexOption.IGNORE_CASE)
    private val COORDS = Regex("^\\d+\\s*[,，]\\s*\\d+$")
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val QUOTED = Regex("「([^」]+)」|\"([^\"]+)\"")
    private val CHAT_APPS = setOf("com.tencent.mm", "com.tencent.mobileqq")
    private val CHROME = setOf(
        "红包", "发红包", "发送", "转账", "搜索", "更多", "确定", "取消", "返回", "支付",
        "金额", "祝福", "塞钱进红包", "群聊", "通讯录", "发现", "微信", "完成", "下一步",
        "确认", "关闭", "打开", "表情", "相册", "拍摄", "位置", "语音", "文件", "收藏",
        "收款", "直播", "视频通话", "普通红包", "拼手气红包", "专属红包", "留言", "付钱",
        "我知道了", "允许", "拒绝", "设置", "加号", "键盘", "QQ", "钱包", "收付款", "更多功能",
    )
    private val LABEL_KEYS = listOf("text", "description", "contentDescription", "content-desc", "name")

    fun wantsRemember(utterances: List<String>): Boolean = utterances.any { FORCE.containsMatchIn(it) }

    /**
     * 这一轮自己没有可留下的步骤,但用户说了记住:用最近一次真正做过事的运行。
     * [earlier] 按时间从早到晚,每项是那一轮的目标和运行事件。
     */
    fun fromConversation(
        goal: String,
        events: List<RunEvent>,
        tools: ToolIndex,
        utterances: List<String> = emptyList(),
        earlier: List<Pair<String, List<RunEvent>>> = emptyList(),
    ): ExperienceDraft? {
        val current = distill(goal, events, tools, utterances)
        if (current?.keepSteps == true || !wantsRemember(utterances)) return current
        val linked = earlier.asReversed().firstNotNullOfOrNull { (priorGoal, priorEvents) ->
            distill(priorGoal, priorEvents, tools, utterances)?.takeIf { it.steps.isNotEmpty() && it.keepSteps }
        }
        return linked ?: current
    }

    fun distill(goal: String, events: List<RunEvent>, tools: ToolIndex, utterances: List<String> = emptyList()): ExperienceDraft? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val succeeded = ended?.outcome == "succeeded"
        val forced = wantsRemember(utterances)
        val lines = ActionDigest.of(events, tools)
        val args = argsByStep(events)
        val steps = ArrayList<ExperienceStep>()
        val avoid = ArrayList<String>()
        var appPackage: String? = null
        var appLabel: String? = null
        for (line in lines) {
            val where = line.where?.let { cleanLabel(it) }
            if (appPackage == null && where != null && PACKAGE.matches(where)) appPackage = where
            if (appLabel == null && where != null && !PACKAGE.matches(where)) appLabel = where
            val arg = args[line.step]
            fun arg(key: String) = (arg?.get(key) as? JsonPrimitive)?.contentOrNull
            val pkgArg = arg("package")?.takeIf { PACKAGE.matches(it) }
            if (line.tool.contains("launch") && pkgArg != null) appPackage = pkgArg
            val here = where?.takeIf { PACKAGE.matches(it) } ?: appPackage
            val labels = labelsThrough(events, line.step)
            val tree = arg("ref")?.let { labels[it] }
            val typed = if (line.typed) arg("text")?.takeUnless { it == "***" } else null
            val raw = when {
                line.typed -> typed
                tree != null -> tree
                line.tool.contains("launch") -> pkgArg ?: line.target
                arg("ref") != null -> null
                else -> line.target
            }
            val target = present(raw, chat = here in CHAT_APPS && !line.tool.contains("launch") && raw?.let(PACKAGE::matches) != true)
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
        var intent = cleanIntent(goal) ?: return null
        if (appPackage in CHAT_APPS) intent = redactQuoted(intent)
        if (SensitiveFilter.hit(intent) != null) return null
        val keywords = (Tokenize.keywords(intent) + listOfNotNull(appLabel?.takeUnless { PACKAGE.matches(it) || it == CHAT_PLACEHOLDER }))
            .distinct().take(8)
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
        return cleanLabel("$what$why")
    }

    /** 屏上的短标签。意图用 [cleanIntent],不在这里截成 80 字。 */
    private fun cleanLabel(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "***" } ?: return null
        if (COORDS.matches(text)) return null
        if (SensitiveFilter.hit(text) != null) return null
        val scrubbed = SkillRedactor.scrubNumbers(Redactor.text(text), emptySet()).replace(SkillRedactor.PLACEHOLDER, "<参数>").trim()
        if (scrubbed.isEmpty() || scrubbed == "<参数>") return null
        return scrubbed.take(160).takeIf { SensitiveFilter.hit(it) == null }
    }

    private fun cleanIntent(raw: String): String? {
        val text = Redactor.text(raw).trim()
        if (text.isEmpty() || SensitiveFilter.hit(text) != null) return null
        val scrubbed = SkillRedactor.scrubNumbers(text, emptySet()).replace(SkillRedactor.PLACEHOLDER, "<参数>").trim()
        return scrubbed.take(INTENT_LIMIT).takeIf { it.isNotEmpty() && SensitiveFilter.hit(it) == null }
    }

    private fun present(raw: String?, chat: Boolean): String? {
        val text = cleanLabel(raw) ?: return null
        if (PACKAGE.matches(text)) return text
        if (chat && !chrome(text)) return CHAT_PLACEHOLDER
        return text
    }

    private fun chrome(text: String) = text.trim() in CHROME

    private fun redactQuoted(intent: String): String = QUOTED.replace(intent) { m ->
        val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@replace m.value
        if (chrome(inner)) m.value else m.value.replace(inner, CHAT_PLACEHOLDER)
    }

    private fun argsByStep(events: List<RunEvent>): Map<Int, JsonObject> {
        val out = HashMap<Int, JsonObject>()
        for (e in events) {
            if (e !is RunEvent.ModelReply || e.argsJson == null) continue
            val o = runCatching { Json.parseToJsonElement(e.argsJson) as? JsonObject }.getOrNull() ?: continue
            out[e.step] = o
        }
        return out
    }

    /** 这一步之前(含这一步的读屏)界面树上 ref 对应的可见文字。后读到的覆盖先读到的。 */
    private fun labelsThrough(events: List<RunEvent>, step: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in events) {
            if (e !is RunEvent.ToolResult || e.step > step) continue
            val el = runCatching { Json.parseToJsonElement(e.resultJson) }.getOrNull() ?: continue
            walk(el, out)
        }
        return out
    }

    private fun walk(el: JsonElement, out: MutableMap<String, String>) {
        when (el) {
            is JsonObject -> {
                val ref = (el["ref"] as? JsonPrimitive)?.contentOrNull
                val label = LABEL_KEYS.firstNotNullOfOrNull { key ->
                    (el[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (ref != null && label != null) out[ref] = label
                el.values.forEach { walk(it, out) }
            }
            is JsonArray -> el.forEach { walk(it, out) }
            else -> Unit
        }
    }

    private const val CHAT_PLACEHOLDER = "<聊天>"
    private const val INTENT_LIMIT = 400
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
