package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole

/** Personal preset; called once on an authorized private build. No network calls or fake verification. */
object OpenRouterDefaults {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    const val MAIN_MODEL = "qwen/qwen3-vl-30b-a3b-instruct"
    const val TRANSCRIPTION_URL = "$BASE_URL/audio/transcriptions"
    const val TRANSCRIPTION_MODEL = "openai/whisper-1"

    fun install(store: ProviderStore, key: String) {
        require(key.isNotBlank())
        val provider = store.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, BASE_URL, key,
            listOf(ListedModel(MAIN_MODEL)))
        val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 262_144, reasoning = false)
        check(store.use(ModelRole.Main, provider.id, MAIN_MODEL, capabilities))
        check(store.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        check(store.choose(ModelRole.Light, RoleChoice.SameAsMain))
    }
}
