package com.wangzan101.mika.relay

import android.content.SharedPreferences
import com.wangzan101.mika.core.relay.DEFAULT_MCP_URL
import com.wangzan101.mika.core.relay.DEFAULT_RELAY_AGENT_TYPE
import com.wangzan101.mika.core.relay.DEFAULT_RELAY_ALIAS
import com.wangzan101.mika.core.relay.RelayConfig
import com.wangzan101.mika.settings.KeyCipher
import java.util.Base64

/** 设置页上能看到的 Hub Relay 状态。令牌本身不在这里。 */
data class HubRelaySnapshot(
    val enabled: Boolean,
    val mcpUrl: String,
    val hasToken: Boolean,
    val tokenUnreadable: Boolean,
    val joinCode: String,
    val alias: String,
    val agentType: String,
    val uploadScreenshots: Boolean,
    val claimTasks: Boolean,
    val agentId: String,
    val sessionId: String,
    val lastError: String?,
    val lastSeq: Long,
)

/**
 * Hub Relay 的开关、地址、加入码和会话编号。Bearer 令牌经 [cipher] 加密后另存,读不出来就当没有,不把密文当令牌用。
 */
class HubRelaySettings(private val prefs: SharedPreferences, private val cipher: KeyCipher) {
    fun snapshot(): HubRelaySnapshot {
        val raw = prefs.getString(TOKEN, null)
        val token = decodeToken(raw)
        return HubRelaySnapshot(
            enabled = prefs.getBoolean(ENABLED, false),
            mcpUrl = mcpUrl(),
            hasToken = raw != null && token != null,
            tokenUnreadable = raw != null && token == null,
            joinCode = prefs.getString(JOIN_CODE, "").orEmpty(),
            alias = text(ALIAS, DEFAULT_RELAY_ALIAS),
            agentType = text(AGENT_TYPE, DEFAULT_RELAY_AGENT_TYPE),
            uploadScreenshots = prefs.getBoolean(UPLOAD, false),
            claimTasks = prefs.getBoolean(CLAIM, false),
            agentId = prefs.getString(AGENT, "").orEmpty(),
            sessionId = prefs.getString(SESSION, "").orEmpty(),
            lastError = prefs.getString(LAST_ERROR, null)?.takeIf { it.isNotBlank() },
            lastSeq = prefs.getLong(LAST_SEQ, 0L),
        )
    }

    fun config(): RelayConfig {
        val snap = snapshot()
        return RelayConfig(
            enabled = snap.enabled,
            mcpUrl = snap.mcpUrl,
            token = token().orEmpty(),
            tokenUnreadable = snap.tokenUnreadable,
            alias = snap.alias,
            agentType = snap.agentType,
            joinCode = snap.joinCode.trim(),
            sessionId = snap.sessionId.trim(),
            agentId = snap.agentId,
            uploadScreenshots = snap.uploadScreenshots,
            claimTasks = snap.claimTasks,
        )
    }

    fun setEnabled(on: Boolean) = prefs.edit().putBoolean(ENABLED, on).commit()

    fun setMcpUrl(url: String) = prefs.edit().putString(BASE, url.trim()).commit()

    fun setJoinCode(code: String) = prefs.edit().putString(JOIN_CODE, code.trim()).commit()

    fun setAlias(alias: String) = prefs.edit().putString(ALIAS, alias.trim()).commit()

    fun setAgentType(agentType: String) = prefs.edit().putString(AGENT_TYPE, agentType.trim()).commit()

    fun setUploadScreenshots(on: Boolean) = prefs.edit().putBoolean(UPLOAD, on).commit()

    fun setClaimTasks(on: Boolean) = prefs.edit().putBoolean(CLAIM, on).commit()

    fun setAgentId(id: String) = prefs.edit().putString(AGENT, id.trim()).commit()

    fun setSessionId(id: String) = prefs.edit().putString(SESSION, id.trim()).commit()

    fun setLastSeq(seq: Long) = prefs.edit().putLong(LAST_SEQ, seq).commit()

    fun setLastError(message: String?) {
        val edit = prefs.edit()
        if (message.isNullOrBlank()) edit.remove(LAST_ERROR) else edit.putString(LAST_ERROR, message.take(300))
        edit.commit()
    }

    /** 空串表示清掉。加密失败返回 false,不把明文写进设置。 */
    fun setToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            prefs.edit().remove(TOKEN).commit()
            return true
        }
        val encoded = try {
            Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            setLastError("token unreadable")
            return false
        }
        prefs.edit().putString(TOKEN, encoded).commit()
        return true
    }

    fun token(): String? = decodeToken(prefs.getString(TOKEN, null))

    private fun mcpUrl(): String = prefs.getString(BASE, null)?.trim()?.trimEnd('/')?.ifBlank { null } ?: DEFAULT_MCP_URL

    private fun text(key: String, fallback: String): String = prefs.getString(key, null)?.trim()?.ifBlank { null } ?: fallback

    private fun decodeToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(raw)), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val ENABLED = "enabled"
        const val BASE = "base_url"
        const val TOKEN = "token"
        const val JOIN_CODE = "join_code"
        const val ALIAS = "alias"
        const val AGENT_TYPE = "agent_type"
        const val UPLOAD = "upload_screenshots"
        const val CLAIM = "claim_tasks"
        const val AGENT = "agent_id"
        const val SESSION = "session_id"
        const val LAST_ERROR = "last_error"
        const val LAST_SEQ = "last_seq"
    }
}
