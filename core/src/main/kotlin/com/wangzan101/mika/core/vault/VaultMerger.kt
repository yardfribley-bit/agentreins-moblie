package com.wangzan101.mika.core.vault

/**
 * 把一条新记录并进已有笔记。主人写过的字留下来:只改我们自己的前置字段,
 * 正文在「AgentReins 记录」一节末尾追加;对不上时整段接在文件最后,不删旧字。
 * 带冲突标记的文件只追加,不重排。
 */
object VaultMerger {
    const val CONFLICT = "<<<<<<<"

    data class FrontPatch(
        val updated: String,
        val created: String,
        val type: String,
        val tags: List<String>,
        val aliases: List<String> = emptyList(),
        val app: String? = null,
        val sources: List<String> = emptyList(),
        val extra: List<Pair<String, String>> = emptyList(),
        val title: String = "笔记",
    )

    fun renderEntry(heading: String, goal: String, outcome: VaultOutcome, steps: List<String>, pitfalls: List<String>, tips: List<String>, runLink: String?): String =
        buildString {
            append(heading).append("\n\n")
            if (goal.isNotBlank()) append("请求：").append(goal.trim()).append("\n\n")
            if (outcome == VaultOutcome.Failure) append("这次没做成。下面是坑,不是可以照搬的成功步骤。\n\n")
            if (steps.isNotEmpty()) {
                append(if (outcome == VaultOutcome.Failure) "试过的步骤(没做成):\n" else "做成的步骤:\n")
                steps.forEach { append("- ").append(it.trim()).append('\n') }
                append('\n')
            }
            if (pitfalls.isNotEmpty()) {
                append(if (outcome == VaultOutcome.Failure) "坑:\n" else "踩过的坑:\n")
                pitfalls.forEach { append("- ").append(it.trim()).append('\n') }
                append('\n')
            }
            if (tips.isNotEmpty()) {
                append("下次可以用的提示:\n")
                tips.forEach { append("- ").append(it.trim()).append('\n') }
                append('\n')
            }
            if (!runLink.isNullOrBlank()) append("来源：").append(runLink).append("\n")
        }.trimEnd() + "\n"

    fun mergeKnowledge(existing: String?, entry: String, runId: String, patch: FrontPatch): String {
        if (existing.isNullOrBlank()) return newKnowledge(entry, patch)
        if (existing.contains("run:$runId")) return existing
        if (existing.contains(CONFLICT)) return appendOnly(existing, entry)
        val (front, body) = split(existing)
        val merged = assemble(updateFront(front, patch), insertRecord(body, entry))
        return if (preserves(existing, merged)) merged else appendOnly(existing, entry)
    }

    /** 两个标记之间换成 [inner];标记外的字不动。没有标记就接在末尾。 */
    fun replaceMarked(existing: String?, start: String, end: String, inner: String, prologue: String): String {
        val block = "$start\n${inner.trim()}\n$end"
        if (existing.isNullOrBlank()) return prologue.trimEnd() + "\n\n" + block + "\n"
        if (existing.contains(CONFLICT)) return appendOnly(existing, block)
        val s = existing.indexOf(start)
        val e = if (s >= 0) existing.indexOf(end, s + start.length) else -1
        if (s >= 0 && e > s) {
            return existing.substring(0, s) + block + existing.substring(e + end.length)
        }
        return existing.trimEnd() + "\n\n" + block + "\n"
    }

    fun preserves(before: String, after: String): Boolean {
        val (fb, bb) = split(before)
        val (fa, ba) = split(after)
        if (!subsequence(bb.lines().filter { it.isNotBlank() }, ba.lines().filter { it.isNotBlank() })) return false
        val merged = setOf("updated", "tags", "aliases", "sources")
        fun key(line: String) = line.trimStart().substringBefore(":").trim()
        if (!subsequence(fb.orEmpty().filter { key(it) !in merged }, fa.orEmpty())) return false
        for (line in fb.orEmpty().filter { key(it) in setOf("tags", "aliases", "sources") }) {
            val now = fa.orEmpty().firstOrNull { key(it) == key(line) } ?: return false
            if (!tokens(line).all { it in tokens(now) }) return false
        }
        return true
    }

    private fun tokens(line: String): List<String> {
        val raw = line.substringAfter(":").trim()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return listOf(raw)
        return raw.removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    private fun newKnowledge(entry: String, patch: FrontPatch): String = buildString {
        append(frontText(null, patch)).append("\n\n")
        append("# ").append(patch.title.lineSequence().first().take(40)).append("\n\n")
        append("可以在这里写自己的备注。下面的记录只追加,不改你写过的字。\n\n")
        append(VaultPaths.RECORD).append("\n\n")
        append(entry.trim()).append('\n')
    }

    private fun insertRecord(body: String, entry: String): String {
        val lines = body.lines().toMutableList()
        val idx = lines.indexOfFirst { it.trim() == VaultPaths.RECORD }
        if (idx < 0) {
            val base = body.trimEnd()
            val gap = if (base.isEmpty()) "" else "$base\n\n"
            return gap + VaultPaths.RECORD + "\n\n" + entry.trim() + "\n"
        }
        var end = lines.size
        for (i in idx + 1 until lines.size) {
            if (lines[i].startsWith("## ") && !lines[i].startsWith("### ")) {
                end = i
                break
            }
        }
        val block = mutableListOf<String>()
        if (end > 0 && lines[end - 1].isNotBlank()) block += ""
        block += entry.trim().lines()
        if (end < lines.size && lines[end].isNotBlank()) block += ""
        lines.addAll(end, block)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun appendOnly(existing: String, entry: String): String =
        existing.trimEnd() + "\n\n" + entry.trim() + "\n"

    fun split(text: String): Pair<List<String>?, String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF").lines()
        if (lines.firstOrNull()?.trim() != "---") return null to lines.joinToString("\n")
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null to lines.joinToString("\n")
        return lines.subList(1, close) to lines.drop(close + 1).joinToString("\n")
    }

    private fun assemble(front: List<String>, body: String): String =
        "---\n" + front.joinToString("\n") + "\n---\n" + body.trimStart('\n').let { if (it.endsWith("\n")) it else "$it\n" }

    private fun updateFront(existing: List<String>?, patch: FrontPatch): List<String> {
        if (existing == null) return frontLines(patch)
        val lines = existing.toMutableList()
        fun setScalar(key: String, value: String, overwrite: Boolean) {
            val i = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
            val line = "$key: $value"
            if (i < 0) lines += line else if (overwrite) lines[i] = line
        }
        setScalar("type", patch.type, overwrite = false)
        setScalar("created", patch.created, overwrite = false)
        setScalar("updated", patch.updated, overwrite = true)
        patch.app?.let { setScalar("app", it, overwrite = false) }
        patch.extra.forEach { (k, v) -> setScalar(k, v, overwrite = false) }
        mergeBracket(lines, "tags", patch.tags)
        mergeBracket(lines, "aliases", patch.aliases)
        mergeBracket(lines, "sources", patch.sources.map { if (it.startsWith("[[")) "\"$it\"" else it })
        return lines
    }

    private fun frontText(existing: List<String>?, patch: FrontPatch): String =
        "---\n" + updateFront(existing, patch).joinToString("\n") + "\n---"

    private fun frontLines(patch: FrontPatch): List<String> = buildList {
        add("type: ${patch.type}")
        add("created: ${patch.created}")
        add("updated: ${patch.updated}")
        add("tags: [${patch.tags.joinToString(", ")}]")
        if (patch.aliases.isNotEmpty()) add("aliases: [${patch.aliases.joinToString(", ") { "\"${it.replace("\"", "")}\"" }}]")
        patch.app?.let { add("app: $it") }
        if (patch.sources.isNotEmpty()) add("sources: [${patch.sources.joinToString(", ") { "\"$it\"" }}]")
        patch.extra.forEach { (k, v) -> add("$k: $v") }
    }

    private fun mergeBracket(lines: MutableList<String>, key: String, add: List<String>) {
        if (add.isEmpty()) return
        val i = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (i < 0) {
            lines += "$key: [${add.joinToString(", ") { if (key == "tags") it else "\"${it.trim('"')}\"" }}]"
            return
        }
        val raw = lines[i].substringAfter(":").trim()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return
        val had = raw.removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val merged = (had + add.map { it.trim().trim('"') }).distinct()
        val quote = key != "tags"
        lines[i] = "$key: [${merged.joinToString(", ") { if (quote) "\"$it\"" else it }}]"
    }

    private fun subsequence(need: List<String>, hay: List<String>): Boolean {
        var i = 0
        for (line in need) {
            val j = hay.subList(i, hay.size).indexOf(line)
            if (j < 0) return false
            i += j + 1
        }
        return true
    }
}
