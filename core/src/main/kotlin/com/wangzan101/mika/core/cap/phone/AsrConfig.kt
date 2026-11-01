package com.wangzan101.mika.core.cap.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Speech-to-text endpoint, model and key for live listening. The key stays out of [toString]. */
data class AsrStored(
    val endpoint: String,
    val model: String,
    val key: String,
    val microphone: Boolean,
) {
    override fun toString(): String =
        "AsrStored(endpoint=$endpoint, model=$model, microphone=$microphone, keySet=${key.isNotBlank()}, keyHint=${AsrConfig.hint(key)})"
}

/** What the model and the UI may show. Never the full API key. */
data class AsrPublic(
    val endpoint: String,
    val model: String,
    val preset: String,
    val keySet: Boolean,
    val keyHint: String,
    val microphone: Boolean,
    val warning: String?,
)

/** Fields the user or the agent wants to change. Null means leave the stored value. */
data class AsrSetupRequest(
    val preset: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
    val key: String? = null,
    val paste: String? = null,
    val microphone: Boolean? = null,
) {
    override fun toString(): String =
        "AsrSetupRequest(preset=$preset, endpoint=$endpoint, model=$model, microphone=$microphone, key=${if (key.isNullOrBlank()) "absent" else "set"}, paste=${if (paste.isNullOrBlank()) "absent" else "set"})"
}

data class AsrApplyResult(
    val ok: Boolean,
    val code: String?,
    val message: String?,
    val view: AsrPublic,
)

/** Pieces recognized in a pasted block. Absent pieces are null. */
data class AsrParsed(
    val endpoint: String? = null,
    val model: String? = null,
    val key: String? = null,
    val presetId: String? = null,
) {
    fun hasAny(): Boolean = endpoint != null || model != null || key != null || presetId != null
}

/** Key provider and endpoint provider disagree, so a transcription call is likely to return HTTP 401. */
data class AsrMismatch(val keyKind: String, val endpointKind: String)

sealed class AsrResolve {
    data class Ready(val stored: AsrStored) : AsrResolve()
    data class Failed(val code: String, val message: String) : AsrResolve()
}

enum class EndpointProblem { Empty, NotUrl, NotHttps, UserInfo, SecretQuery }

/** OpenRouter and OpenAI transcription presets, plus parsing a pasted URL / model / key block. */
object AsrPresets {
    data class Preset(val id: String, val endpoint: String, val model: String)

    val openRouter = Preset(
        "openrouter",
        "https://openrouter.ai/api/v1/audio/transcriptions",
        "openai/whisper-large-v3",
    )
    val openAi = Preset(
        "openai",
        "https://api.openai.com/v1/audio/transcriptions",
        "whisper-1",
    )
    val all = listOf(openRouter, openAi)

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Exact preset, after URL canonicalization. A custom model on a known host is not a preset. */
    fun matching(endpoint: String, model: String): Preset? {
        val canonical = canonical(endpoint) ?: return null
        val name = model.trim()
        return all.firstOrNull { canonical(it.endpoint) == canonical && it.model == name }
    }

    /** `sk-or-` is OpenRouter. Any other `sk-` key is OpenAI. */
    fun forKey(key: String): Preset? = when (AsrConfig.keyKind(key)) {
        "openrouter" -> openRouter
        "openai" -> openAi
        else -> null
    }

    fun canonical(url: String): String? {
        val http = url.trim().toHttpUrlOrNull() ?: return null
        return http.newBuilder().username("").password("").build().toString().trimEnd('/')
    }
}

object AsrConfig {
    fun normalizePreset(raw: String): String? = when (
        raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")
    ) {
        "openrouter" -> "openrouter"
        "openai" -> "openai"
        "custom", "自定义" -> "custom"
        else -> null
    }

    fun keyKind(key: String): String? {
        val value = key.trim()
        return when {
            value.startsWith("sk-or-") -> "openrouter"
            value.startsWith("sk-") -> "openai"
            else -> null
        }
    }

    fun endpointKind(url: String): String? {
        val host = url.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return null
        return when {
            host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> "openrouter"
            host == "api.openai.com" -> "openai"
            else -> null
        }
    }

    fun mismatch(endpoint: String, key: String): AsrMismatch? {
        val keyProvider = keyKind(key) ?: return null
        val endpointProvider = endpointKind(endpoint) ?: return null
        if (keyProvider == endpointProvider) return null
        return AsrMismatch(keyProvider, endpointProvider)
    }

    /** English note for the model. The prefix `sk-or-` is not a secret. */
    fun warning(mismatch: AsrMismatch): String = when (mismatch.keyKind) {
        "openrouter" -> "The key prefix sk-or- is OpenRouter, but the endpoint is OpenAI. A transcription call will likely return HTTP 401. Use preset openrouter unless this URL is intentional."
        else -> "The key looks like OpenAI, but the endpoint is OpenRouter. A transcription call will likely return HTTP 401. Use preset openai unless this URL is intentional."
    }

    /**
     * Short label for a saved key: a public prefix plus the last four characters.
     * Keys shorter than 12 characters are only "saved", so the hint cannot be the whole key.
     */
    fun hint(key: String): String {
        val value = key.trim()
        if (value.isEmpty()) return ""
        if (value.length < 12) return "saved"
        val head = when {
            value.startsWith("sk-or-") -> "sk-or-"
            value.startsWith("sk-proj-") -> "sk-proj-"
            value.startsWith("sk-") -> "sk-"
            else -> value.take(2)
        }
        return head + "…" + value.takeLast(4)
    }

    fun publish(stored: AsrStored): AsrPublic {
        val mismatch = mismatch(stored.endpoint, stored.key)
        return AsrPublic(
            endpoint = stored.endpoint,
            model = stored.model,
            preset = AsrPresets.matching(stored.endpoint, stored.model)?.id ?: "custom",
            keySet = stored.key.isNotBlank(),
            keyHint = hint(stored.key),
            microphone = stored.microphone,
            warning = mismatch?.let { warning(it) },
        )
    }

    fun endpointProblem(url: String): EndpointProblem? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return EndpointProblem.Empty
        val http = trimmed.toHttpUrlOrNull() ?: return EndpointProblem.NotUrl
        if (!http.isHttps) return EndpointProblem.NotHttps
        if (http.username.isNotEmpty() || http.password.isNotEmpty()) return EndpointProblem.UserInfo
        if (http.queryParameterNames.any { secretQueryName(it) }) return EndpointProblem.SecretQuery
        return null
    }

    fun endpointMessage(problem: EndpointProblem): String = when (problem) {
        EndpointProblem.Empty -> "endpoint is required"
        EndpointProblem.NotUrl -> "endpoint must be a full HTTPS URL"
        EndpointProblem.NotHttps -> "endpoint must use HTTPS"
        EndpointProblem.UserInfo -> "endpoint must not include a username or password"
        EndpointProblem.SecretQuery -> "endpoint must not include a key or token query parameter; put the key in the key field"
    }

    /**
     * Apply an edit on top of [current].
     * A key alone selects the matching preset when the current URL and model are still a known preset (or blank).
     * An explicit URL or model is kept. Mismatches are saved and reported; the caller must not call the API until the user confirms.
     */
    fun resolve(current: AsrStored, request: AsrSetupRequest, lockCustom: Boolean = false): AsrResolve {
        val pasted = request.paste?.let { AsrConfigText.parse(it) } ?: AsrParsed()
        val presetRaw = request.preset ?: pasted.presetId
        val presetId = presetRaw?.let { normalizePreset(it) }
        if (presetRaw != null && presetId == null) {
            return AsrResolve.Failed("invalid_preset", "preset must be openrouter, openai, or custom")
        }
        val endpoint = first(request.endpoint, pasted.endpoint)
        val model = first(request.model, pasted.model)
        val key = first(request.key, pasted.key)
        if (endpoint != null && endpoint.length > 2000) return AsrResolve.Failed("invalid_endpoint", "endpoint is too long")
        if (model != null && model.length > 200) return AsrResolve.Failed("invalid_model", "model is too long")
        if (key != null && key.length > 2000) return AsrResolve.Failed("invalid_key", "key is too long")
        val filled = fill(current, presetId, endpoint, model, key, lockCustom || presetId == "custom")
        val problem = endpointProblem(filled.endpoint)
        if (problem != null) return AsrResolve.Failed("invalid_endpoint", endpointMessage(problem))
        if (filled.model.isBlank()) return AsrResolve.Failed("invalid_model", "model is required")
        if (filled.model.length > 200 || '\n' in filled.model || '\r' in filled.model) {
            return AsrResolve.Failed("invalid_model", "model is invalid")
        }
        val microphone = request.microphone ?: current.microphone
        return AsrResolve.Ready(filled.copy(microphone = microphone))
    }

    /**
     * Same fill rules as [resolve], but keep a pasted URL even when it is not valid yet
     * so the settings screen can show what the user pasted.
     */
    fun merge(current: AsrStored, parsed: AsrParsed, lockCustom: Boolean): AsrStored =
        fill(current, parsed.presetId, parsed.endpoint, parsed.model, parsed.key, lockCustom || parsed.presetId == "custom")

    private fun fill(
        current: AsrStored,
        presetId: String?,
        endpoint: String?,
        model: String?,
        key: String?,
        lockCustom: Boolean,
    ): AsrStored {
        val chosen = presetId?.let { AsrPresets.byId(it) }
        var nextEndpoint = endpoint?.trim() ?: current.endpoint
        var nextModel = model?.trim() ?: current.model
        val nextKey = (key ?: current.key).trim()
        if (chosen != null) {
            if (endpoint == null) nextEndpoint = chosen.endpoint
            if (model == null) nextModel = chosen.model
        } else if (!lockCustom && endpoint == null && model == null) {
            val implied = AsrPresets.forKey(nextKey)
            if (implied != null && (nextEndpoint.isBlank() || AsrPresets.matching(nextEndpoint, nextModel) != null)) {
                nextEndpoint = implied.endpoint
                nextModel = implied.model
            }
        }
        return current.copy(endpoint = nextEndpoint.trim(), model = nextModel.trim(), key = nextKey)
    }

    private fun first(explicit: String?, pasted: String?): String? = explicit?.trim()?.ifBlank { null } ?: pasted
}

/** One pasted block: a URL, a model id, an API key, or any mix of those, including JSON. */
object AsrConfigText {
    private val json = Json { ignoreUnknownKeys = true }
    private val urlPattern = Regex("""https://[^\s<>"'`，,;；]+""", RegexOption.IGNORE_CASE)
    private val keyPattern = Regex("""(?<![A-Za-z0-9])((?:sk|rk)-[A-Za-z0-9_\-]{8,})(?![A-Za-z0-9_\-])""")
    private val labeledModel = Regex(
        """(?i)(?:^|[\s,{])(?:model|模型|asr_model|transcription_model)\s*[:：=]\s*["']?([A-Za-z0-9_./:-]+)""",
    )
    private val labeledKey = Regex(
        """(?i)(?:^|[\s,{])(?:api[ _-]?key|apikey|key|token|secret|密钥)\s*[:：=]\s*["']?(\S+)""",
    )
    private val labeledPreset = Regex(
        """(?i)(?:^|[\s,{])(?:preset|provider|服务商)\s*[:：=]\s*["']?(openrouter|openai|custom|自定义)""",
    )
    private val modelToken = Regex(
        """^[A-Za-z0-9][A-Za-z0-9._:-]{0,120}(?:/[A-Za-z0-9][A-Za-z0-9._:-]{0,80})+$|^whisper-[A-Za-z0-9._-]+$|^(?:gpt|whisper|transcribe)[A-Za-z0-9._:-]+$""",
    )

    fun parse(raw: String): AsrParsed {
        val text = unwrap(raw)
        if (text.isEmpty()) return AsrParsed()
        parseJson(text)?.let { return it }
        val embedded = Regex("""\{[\s\S]*\}""").find(text)?.value
        if (embedded != null && embedded != text) parseJson(embedded)?.let { return it }
        return parseLoose(text)
    }

    /** A paste that carries more than one field, or several lines with at least one field. */
    fun looksLikeBundle(text: String): Boolean {
        val parsed = parse(text)
        val parts = listOfNotNull(parsed.endpoint, parsed.model, parsed.key)
        if (parts.size >= 2) return true
        return '\n' in text && parts.isNotEmpty() && text.lineSequence().count { it.isNotBlank() } >= 2
    }

    private fun parseJson(text: String): AsrParsed? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            obj.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?.let { it as? JsonPrimitive }?.contentOrNull?.let { tidy(it) }?.ifBlank { null }
        }
        val endpoint = value("endpoint", "url", "base_url", "baseUrl", "api_url", "transcription_url")?.let { cleanUrl(it) }
        val model = value("model", "asr_model", "transcription_model")
        val key = value("key", "api_key", "apiKey", "token", "secret")
        val preset = value("preset", "provider")?.let { AsrConfig.normalizePreset(it) }
        if (endpoint == null && model == null && key == null && preset == null) return null
        return AsrParsed(endpoint = endpoint, model = model, key = key, presetId = preset)
    }

    private fun parseLoose(text: String): AsrParsed {
        val endpoint = labeledUrl(text) ?: urlPattern.find(text)?.value?.let { cleanUrl(it) }
        val labeled = labeledKey.find(text)?.groupValues?.get(1)?.let { tidy(it) }
            ?.takeIf { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
        val key = labeled ?: keyPattern.find(text)?.groupValues?.get(1)
        val model = labeledModel.find(text)?.groupValues?.get(1)?.let { tidy(it) }?.ifBlank { null }
            ?: unlabeledModel(text, endpoint, key)
        val preset = labeledPreset.find(text)?.groupValues?.get(1)?.let { AsrConfig.normalizePreset(it) }
            ?: text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
                AsrConfig.normalizePreset(line).takeIf { line.equals("openrouter", true) || line.equals("openai", true) || line == "自定义" || line.equals("custom", true) }
            }
        return AsrParsed(endpoint = endpoint, model = model, key = key, presetId = preset)
    }

    private fun labeledUrl(text: String): String? {
        val match = Regex("""(?i)(?:^|[\s,{])(?:url|endpoint|base_url|baseurl|api_url|地址|接口地址|接口)\s*[:：=]\s*["']?(https://\S+)""")
            .find(text) ?: return null
        return cleanUrl(match.groupValues[1])
    }

    private fun unlabeledModel(text: String, endpoint: String?, key: String?): String? {
        val skip = setOfNotNull(endpoint, key).map { it.trim() }.toSet()
        return text.split(Regex("""[\s,，;；]+""")).map { tidy(it) }.firstOrNull { token ->
            token.isNotBlank() && token !in skip && !token.startsWith("http", ignoreCase = true) &&
                keyPattern.matchEntire(token) == null && modelToken.matches(token)
        }
    }

    fun cleanUrl(raw: String): String {
        var value = tidy(raw)
        while (value.lastOrNull() in trailing) value = value.dropLast(1)
        val http = value.toHttpUrlOrNull() ?: return value.trimEnd('/')
        val builder = http.newBuilder().username("").password("")
        for (name in http.queryParameterNames) if (secretQueryName(name)) builder.removeAllQueryParameters(name)
        return builder.build().toString().trimEnd('/')
    }

    private fun tidy(value: String): String = value.trim().trim('"', '\'', '`', ',', ';', '，', '；', '。', ')')

    private fun unwrap(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val nl = text.indexOf('\n')
            if (nl >= 0) text = text.substring(nl + 1)
            text = text.removeSuffix("```")
        }
        return text.trim()
    }

    private val trailing = setOf('.', ',', ';', ':', ')', ']', '}', '>', '，', '。', '；')
}

private val SECRET_QUERY_NAMES = setOf("key", "apikey", "token", "secret", "accesskey", "accesstoken")

private fun secretQueryName(name: String): Boolean =
    name.lowercase().replace("_", "").replace("-", "") in SECRET_QUERY_NAMES

/** Phone-side store for live-listening speech settings. The tool result must not include the key. */
interface LiveAudioSetup {
    fun view(): AsrPublic
    fun apply(request: AsrSetupRequest): AsrApplyResult
}
