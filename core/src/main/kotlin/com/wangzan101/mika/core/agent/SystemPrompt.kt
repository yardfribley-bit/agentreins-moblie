package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.prompt.Prompts

/**
 * 系统提示词:P0 身份(prompts/system/identity.md)、P1 通用准则(system/principles.md,不点名工具),
 * 接通用工具的总规则(capabilities/general.md,通用工具一直都在),再接核心自己与各能力给的段落。
 * 段落只在那组工具出现在本任务发给模型的工具列表里时才有:说明和工具出自同一个判断。
 * 任务开始时拼一次,任务内一字不变。
 */
object SystemPrompt {
    fun identity(): String = listOf(Prompts.system("identity"), Prompts.system("principles"), read(GENERAL)).joinToString("\n\n")

    fun xIdentity(): String {
        val base = Prompts.system("identity")
        val how = base.substringAfter("How you work:")
        val identity = X_IDENTITY + "\n\nHow you work:" + how
        return listOf(identity, Prompts.system("principles"), read(GENERAL)).joinToString("\n\n")
    }

    private val X_IDENTITY = """You are X 小助手, the user's phone assistant focused on X (Twitter). Your main work is
finding relevant posts and commenting, creating and publishing posts, using available
AI tools to prepare content, replying to comments, and collecting and organizing posts
in the user's areas of interest. Use relevant past task memories when available. X's
Android package is com.twitter.android. Other apps and services can support the work;
check whether they are actually accessible before claiming you can use them.

You are running inside the separate app X 小助手 (Android package com.agentreins.x).
AgentReins Mobile (com.wangzan101.mika) is a different installed app, with its own
settings, model provider, account, memory and permissions. Never inspect that app's
settings or report its model or balance as your own. For your own settings or model
balance, use an explicitly available tool that reports your configuration; otherwise
ask the user to check X 小助手's settings or its actual provider account. Do not guess.

You are not a programming assistant. If a task calls for coding, use a suitable
available app such as Trae when the user asks for that work. Focus on operating X,
research, writing, publishing, and organizing the user's work.
""".trimIndent()

    fun assemble(identity: String, sections: List<String>): String = (listOf(identity) + sections).joinToString("\n\n")

    private const val GENERAL = "general"

    /** 一段能力说明。 */
    internal fun read(name: String): String = Prompts.capability(name)
}

/**
 * 核心自己的段落,排在各能力之前:用到才展开的几组从哪个入口进(groups,只列本任务给了入口的;展开后照旧留着,系统提示词少变);
 * 有两块以上的屏时怎么选、怎么在两块屏之间配合(channels);有屏展开后交给用户、下单发送前的核对(handover);
 * 动作结果自带屏幕时怎么读它(inline-screen);一次多步(batch)。
 */
internal object CoreSections : PromptSections {
    override fun sections(env: PromptEnv): List<String> = listOfNotNull(
        env.gate(SystemPrompt.read("groups")).takeIf { it.lines().size > 1 },
        SystemPrompt.read("channels").takeIf { env.screens >= 2 },
        SystemPrompt.read("handover").takeIf { env.openScreens >= 1 },
        SystemPrompt.read("inline-screen").takeIf { env.inlineScreen && env.openScreens >= 1 },
        SystemPrompt.read("batch").takeIf { DO_STEPS_TOOL in env.offered },
    ).map(env::gate)
}
