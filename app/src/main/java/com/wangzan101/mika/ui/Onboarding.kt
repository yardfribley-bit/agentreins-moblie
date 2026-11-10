package com.wangzan101.mika.ui

import android.content.Context

/** 首次上手：欢迎、模型、手机操作、知识库、示例任务。 */
enum class OnboardingStep { Welcome, Model, Phone, Vault, Sample }

/**
 * 首次打开时走哪几步（纯函数，便于测试）。按「看过没有」判断，不每次都弹：
 * - 欢迎和配置模型：还没有主模型、也没在上手流程里配过模型时才走。
 * - 手机操作：系统够新、手机操作还没就绪、引导没看过时走。
 * - 知识库：还没选文件夹、也没看过这一步时走。可跳过。
 * - 示例任务只出现在走完前面某一步之后，不单独打断已经用过的人。
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** 系统够新且手机操作还没就绪。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
        val vaultChosen: Boolean = false,
        val vaultSeen: Boolean = false,
        val sampleSeen: Boolean = false,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        !i.vaultChosen && !i.vaultSeen -> OnboardingStep.Vault
        else -> null
    }

    /** [step] 做完后的下一步；没有了为 null（进对话）。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> afterSetup(i, includePhone = true)
        OnboardingStep.Phone -> afterSetup(i, includePhone = false)
        OnboardingStep.Vault -> if (!i.sampleSeen) OnboardingStep.Sample else null
        OnboardingStep.Sample -> null
    }

    private fun afterSetup(i: Inputs, includePhone: Boolean): OnboardingStep? = when {
        includePhone && i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        !i.vaultChosen && !i.vaultSeen -> OnboardingStep.Vault
        !i.sampleSeen -> OnboardingStep.Sample
        else -> null
    }

    /** 底部分页点：五步里的第几步。 */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 5
}

/** 上手流程里看过的步骤；存在本机。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)
    fun markModelSetupDone() { prefs.edit().putBoolean(MODEL_DONE, true).apply() }

    fun vaultSeen(): Boolean = prefs.getBoolean(VAULT_SEEN, false)
    fun markVaultSeen() { prefs.edit().putBoolean(VAULT_SEEN, true).apply() }

    fun sampleSeen(): Boolean = prefs.getBoolean(SAMPLE_SEEN, false)
    fun markSampleSeen() { prefs.edit().putBoolean(SAMPLE_SEEN, true).apply() }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
        const val VAULT_SEEN = "vault_seen"
        const val SAMPLE_SEEN = "sample_seen"
    }
}
