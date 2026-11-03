package com.wangzan101.mika.core.cap.phone.speech

import com.wangzan101.mika.core.cap.phone.AsrConfig
import com.wangzan101.mika.core.cap.phone.AsrConfigText
import com.wangzan101.mika.core.cap.phone.AsrParsed
import com.wangzan101.mika.core.cap.phone.AsrPresets

/** Saved speech settings. [allow] defaults off and is never changed by the agent tool. The key stays out of [toString]. */
data class SpeechStored(
    val allow: Boolean = false,
    val provider: String = "online",
    val endpoint: String = SpeechPresets.openRouter.endpoint,
    val model: String = SpeechPresets.openRouter.model,
    val voice: String = SpeechPresets.openRouter.voice,
    val key: String = "",
    val rate: Float = 1f,
    val volume: Float = 1f,
    val route: String = SpeechRoute.SPEAKER,
) {
    override fun toString(): String =
        "SpeechStored(allow=$allow, provider=$provider, endpoint=$endpoint, model=$model, voice=$voice, rate=$rate, volume=$volume, route=$route, keySet=${key.isNotBlank()}, keyHint=${AsrConfig.hint(key)})"
}

data class SpeechPublic(
    val allow: Boolean,
    val provider: String,
    val endpoint: String,
    val model: String,
    val voice: String,
    val preset: String,
    val keySet: Boolean,
    val keyHint: String,
    val rate: Float,
    val volume: Float,
    val route: String,
    val routeAvailable: Boolean,
    val warning: String?,
)

/** Null fields keep the stored value. This request cannot turn [SpeechStored.allow] on. */
data class SpeechSetupRequest(
    val preset: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val key: String? = null,
    val paste: String? = null,
    val rate: Float? = null,
    val volume: Float? = null,
    val route: String? = null,
    val provider: String? = null,
) {
    override fun toString(): String =
        "SpeechSetupRequest(preset=$preset, endpoint=$endpoint, model=$model, voice=$voice, provider=$provider, rate=$rate, volume=$volume, route=$route, key=${if (key.isNullOrBlank()) "absent" else "set"}, paste=${if (paste.isNullOrBlank()) "absent" else "set"})"
}

data class SpeechApplyResult(
    val ok: Boolean,
    val code: String?,
    val message: String?,
    val view: SpeechPublic,
)

sealed class SpeechResolve {
    data class Ready(val stored: SpeechStored) : SpeechResolve()
    data class Failed(val code: String, val message: String) : SpeechResolve()
}

object SpeechPresets {
    data class Preset(val id: String, val endpoint: String, val model: String, val voice: String, val provider: String)

    val openRouter = Preset(
        "openrouter",
        "https://openrouter.ai/api/v1/audio/speech",
        "openai/gpt-4o-mini-tts-2025-12-15",
        "coral",
        "online",
    )
    val openAi = Preset(
        "openai",
        "https://api.openai.com/v1/audio/speech",
        "gpt-4o-mini-tts",
        "coral",
        "online",
    )
    val openAiFast = Preset(
        "openai-fast",
        "https://api.openai.com/v1/audio/speech",
        "tts-1",
        "nova",
        "online",
    )
    val system = Preset("system", "", "", "default", "system")
    val all = listOf(openRouter, openAi, openAiFast, system)

    val miniVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
    val fastVoices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    /** Spoken with the first sentence so Chinese does not come out like a news reader. Sent only to mini-tts models. */
    const val CHINESE_STYLE = "用自然口语中文说，像真人聊天，不要播音腔，不要逐字顿读。"

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    fun matching(provider: String, endpoint: String, model: String, voice: String): Preset? {
        if (provider == "system") return system
        val canonical = AsrPresets.canonical(endpoint) ?: return null
        return all.firstOrNull { preset ->
            preset.provider == "online" &&
                AsrPresets.canonical(preset.endpoint) == canonical &&
                preset.model == model.trim() && preset.voice == voice.trim()
        }
    }

    fun forKey(key: String): Preset? = when (AsrConfig.keyKind(key)) {
        "openrouter" -> openRouter
        "openai" -> openAi
        else -> null
    }
}

object SpeechConfig {
    const val RATE_MIN = 0.5f
    const val RATE_MAX = 2.0f

    fun normalizePreset(raw: String): String? = when (
        raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")
    ) {
        "openrouter" -> "openrouter"
        "openai", "openaiaudio", "natural" -> "openai"
        "openaifast", "tts1", "fast", "低延迟" -> "openai-fast"
        "system", "android", "offline", "系统", "系统语音" -> "system"
        "custom", "自定义" -> "custom"
        else -> null
    }

    fun normalizeProvider(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        null, "" -> null
        "online", "cloud", "在线" -> "online"
        "system", "android", "offline", "系统" -> "system"
        else -> null
    }

    fun publish(stored: SpeechStored): SpeechPublic {
        val route = SpeechRoute.profile(stored.route)
        val mismatch = if (stored.provider == "online" && stored.key.isNotBlank()) AsrConfig.mismatch(stored.endpoint, stored.key) else null
        return SpeechPublic(
            allow = stored.allow,
            provider = stored.provider,
            endpoint = stored.endpoint,
            model = stored.model,
            voice = stored.voice,
            preset = SpeechPresets.matching(stored.provider, stored.endpoint, stored.model, stored.voice)?.id ?: "custom",
            keySet = stored.key.isNotBlank(),
            keyHint = AsrConfig.hint(stored.key),
            rate = stored.rate,
            volume = stored.volume,
            route = route.id,
            routeAvailable = route.available,
            warning = mismatch?.let { AsrConfig.warning(it) },
        )
    }

    fun resolve(current: SpeechStored, request: SpeechSetupRequest, lockCustom: Boolean = false): SpeechResolve {
        val pasted = request.paste?.let { parsePaste(it) }
        val presetRaw = request.preset ?: pasted?.presetId
        val presetId = presetRaw?.let { normalizePreset(it) }
        if (presetRaw != null && presetId == null) {
            return SpeechResolve.Failed("invalid_preset", "preset must be openrouter, openai, openai-fast, system, or custom")
        }
        val provider = normalizeProvider(request.provider ?: pasted?.provider)
        if ((request.provider != null || pasted?.provider != null) && provider == null) {
            return SpeechResolve.Failed("invalid_provider", "provider must be online or system")
        }
        val endpoint = first(request.endpoint, pasted?.endpoint)
        val model = first(request.model, pasted?.model)
        val voice = first(request.voice, pasted?.voice)
        val key = first(request.key, pasted?.key)
        if (endpoint != null && endpoint.length > 2000) return SpeechResolve.Failed("invalid_endpoint", "endpoint is too long")
        if (model != null && model.length > 200) return SpeechResolve.Failed("invalid_model", "model is too long")
        if (voice != null && voice.length > 80) return SpeechResolve.Failed("invalid_voice", "voice is too long")
        if (key != null && key.length > 2000) return SpeechResolve.Failed("invalid_key", "key is too long")
        val rate = request.rate ?: current.rate
        val volume = request.volume ?: current.volume
        if (rate !in RATE_MIN..RATE_MAX) return SpeechResolve.Failed("invalid_rate", "rate must be between 0.5 and 2")
        if (volume !in 0f..1f) return SpeechResolve.Failed("invalid_volume", "volume must be between 0 and 1")
        val route = request.route?.let { SpeechRoute.normalize(it) }
        if (request.route != null && route == null) return SpeechResolve.Failed("invalid_route", "route must be speaker or external")
        val filled = fill(current, presetId, provider, endpoint, model, voice, key, lockCustom || presetId == "custom")
            .copy(rate = rate, volume = volume, route = route ?: current.route, allow = current.allow)
        if (filled.provider == "system") return SpeechResolve.Ready(filled.copy(endpoint = "", model = ""))
        val problem = AsrConfig.endpointProblem(filled.endpoint)
        if (problem != null) return SpeechResolve.Failed("invalid_endpoint", AsrConfig.endpointMessage(problem))
        if (filled.model.isBlank()) return SpeechResolve.Failed("invalid_model", "model is required")
        if (filled.voice.isBlank()) return SpeechResolve.Failed("invalid_voice", "voice is required")
        if (fastModel(filled.model) && filled.voice !in SpeechPresets.fastVoices) {
            return SpeechResolve.Failed("invalid_voice", "tts-1 voices are ${SpeechPresets.fastVoices.joinToString(", ")}")
        }
        return SpeechResolve.Ready(filled)
    }

    fun fastModel(model: String): Boolean {
        val name = model.substringAfterLast('/').lowercase()
        return name == "tts-1" || name == "tts-1-hd"
    }

    /** mini-tts accepts a style instruction. tts-1 does not. */
    fun sendsStyle(model: String): Boolean = !fastModel(model) && (
        model.contains("mini-tts") || model.contains("gpt-4o-mini-tts")
        )

    private fun fill(
        current: SpeechStored,
        presetId: String?,
        provider: String?,
        endpoint: String?,
        model: String?,
        voice: String?,
        key: String?,
        lockCustom: Boolean,
    ): SpeechStored {
        val chosen = presetId?.let { SpeechPresets.byId(it) }
        var nextProvider = provider ?: current.provider
        var nextEndpoint = endpoint?.trim() ?: current.endpoint
        var nextModel = model?.trim() ?: current.model
        var nextVoice = voice?.trim() ?: current.voice
        val nextKey = (key ?: current.key).trim()
        if (chosen != null) {
            nextProvider = chosen.provider
            if (endpoint == null && chosen.provider == "online") nextEndpoint = chosen.endpoint
            if (model == null && chosen.provider == "online") nextModel = chosen.model
            if (voice == null && chosen.voice.isNotBlank()) nextVoice = chosen.voice
        } else if (!lockCustom && endpoint == null && model == null && voice == null && provider == null) {
            val implied = SpeechPresets.forKey(nextKey)
            val stillPreset = nextEndpoint.isBlank() || SpeechPresets.matching(nextProvider, nextEndpoint, nextModel, nextVoice) != null
            if (implied != null && stillPreset) {
                nextProvider = implied.provider
                nextEndpoint = implied.endpoint
                nextModel = implied.model
                nextVoice = implied.voice
            }
        }
        if (nextProvider == "system") return current.copy(provider = "system", key = nextKey, voice = nextVoice.ifBlank { "default" })
        return current.copy(provider = "online", endpoint = nextEndpoint.trim(), model = nextModel.trim(), voice = nextVoice.trim(), key = nextKey)
    }

    private fun first(explicit: String?, pasted: String?): String? = explicit?.trim()?.ifBlank { null } ?: pasted?.trim()?.ifBlank { null }

    private fun parsePaste(raw: String): Paste {
        val parsed: AsrParsed = AsrConfigText.parse(raw)
        val voice = Regex("""(?i)(?:^|[\s,{])(?:voice|声音|音色)\s*[:：=]\s*["']?([A-Za-z0-9_\-]{1,40})""")
            .find(raw)?.groupValues?.get(1)
        val preset = Regex("""(?i)(?:^|[\s,{])(?:preset|provider|服务商)\s*[:：=]\s*["']?([A-Za-z0-9_\-\u4e00-\u9fff]+)""")
            .find(raw)?.groupValues?.get(1)?.let { normalizePreset(it) }
        return Paste(parsed.endpoint, parsed.model, voice, parsed.key, preset, null)
    }

    private data class Paste(
        val endpoint: String?,
        val model: String?,
        val voice: String?,
        val key: String?,
        val presetId: String?,
        val provider: String?,
    )
}

interface SpeechSetup {
    fun view(): SpeechPublic
    fun apply(request: SpeechSetupRequest): SpeechApplyResult
}
