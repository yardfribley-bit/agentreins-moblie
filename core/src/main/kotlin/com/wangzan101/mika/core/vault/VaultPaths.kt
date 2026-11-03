package com.wangzan101.mika.core.vault

/**
 * 库里的相对路径。只允许写在所选子文件夹下面,并且分成两层:
 * [raw] 是一次运行的原始记录(插件把它当不可变来源),
 * [wiki] 是可复用的做法、索引和日志(插件把它当知识页)。
 */
object VaultPaths {
    const val DEFAULT_ROOT = "AgentReins"
    const val RECORD = "## AgentReins 记录"
    const val PROBE_NAME = "测试读写.md"
    const val MAX_RUNS_PER_DAY = 40
    const val MAX_LOG_LINES = 80

    fun normalizeRoot(raw: String?): String {
        val parts = (raw ?: DEFAULT_ROOT).replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
        return parts.joinToString("/").ifBlank { DEFAULT_ROOT }.take(80)
    }

    fun allowed(relative: String, root: String): Boolean {
        val path = relative.replace('\\', '/')
        if (path.startsWith("/") || path.contains("//") || !path.endsWith(".md")) return false
        val parts = path.split("/")
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val r = normalizeRoot(root)
        return path == "$r.md" || path.startsWith("$r/")
    }

    /** 文件名里不能有路径分隔符。汉字留下,其余危险字符换成下划线。 */
    fun safeSegment(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim(' ', '.', '_')
            .take(40)
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "笔记"
        return cleaned
    }

    fun wikiApps(root: String) = "${normalizeRoot(root)}/wiki/apps"
    fun wikiTasks(root: String) = "${normalizeRoot(root)}/wiki/tasks"
    fun appFile(root: String, pkg: String) = "${wikiApps(root)}/${safeSegment(pkg)}.md"
    fun taskFile(root: String, topic: String) = "${wikiTasks(root)}/${safeSegment(topic)}.md"
    fun probeFile(root: String) = "${normalizeRoot(root)}/wiki/$PROBE_NAME"
    fun indexFile(root: String) = "${normalizeRoot(root)}/wiki/index.md"
    fun logFile(root: String) = "${normalizeRoot(root)}/wiki/log.md"
    fun homeFile(root: String) = "${normalizeRoot(root)}/首页.md"
    fun runsDir(root: String) = "${normalizeRoot(root)}/raw/runs"
    fun dayFile(root: String, day: String) = "${runsDir(root)}/$day.md"
    fun runFile(root: String, day: String, time: String, id: String) =
        "${runsDir(root)}/$day/$time-${safeSegment(id)}.md"

    fun topicFrom(goal: String, explicit: String?): String {
        val raw = explicit?.trim().orEmpty().ifBlank {
            com.wangzan101.mika.core.memory.Tokenize.keywords(goal).firstOrNull() ?: goal.trim().take(24)
        }
        return safeSegment(raw)
    }

    /** Obsidian 双链,不带 .md。 */
    fun link(path: String, label: String? = null): String {
        val target = path.removeSuffix(".md")
        val show = label?.trim().orEmpty().ifBlank { target.substringAfterLast('/') }
        return if (show == target.substringAfterLast('/')) "[[$target]]" else "[[$target|$show]]"
    }

    fun isProbe(path: String) = path.endsWith("/$PROBE_NAME")
    fun isRunLog(path: String) = "/raw/runs/" in path.replace('\\', '/')
}
