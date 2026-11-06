package com.wangzan101.mika.ui

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.wangzan101.mika.core.tool.CloudCommandResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** The supplied fingerprint must match before any password is sent. */
internal object CloudSsh {
    /** Error categories avoid exposing credentials or server output in the UI. */
    fun failureMessage(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "hostkey has been changed" in message || "host key has been changed" in message -> "服务器身份发生变化，连接已停止。请核对主机后重新配置。"
            "auth fail" in message || "authentication fail" in message -> "账号或密码验证失败，请检查服务器登录信息。"
            chain.any { it is java.net.UnknownHostException } -> "找不到服务器地址，请检查域名或 IP。"
            chain.any { it is java.net.SocketTimeoutException } || "timed out" in message -> "连接服务器超时，请检查网络和 SSH 端口。"
            "connection refused" in message -> "服务器拒绝连接，请检查 SSH 服务和端口。"
            "connection reset" in message || "connection closed" in message -> "连接在 SSH 握手时被重置，请检查服务器和网络。"
            "algorithm negotiation" in message || "algorithm negotiation fail" in message -> "SSH 加密算法不兼容，请检查服务器 SSH 设置。"
            else -> "连接失败，请检查服务器地址、身份和登录信息。"
        }
    }
    private fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

    /** Obtain the presented host key without authenticating or accepting it. */
    fun probe(host: String, port: Int, user: String): String {
        var found: String? = null
        val ssh = JSch()
        ssh.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                if (key != null) found = fingerprint(key)
                return HostKeyRepository.NOT_INCLUDED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "probe"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val session = ssh.getSession(user, host, port)
        var failure: Throwable? = null
        try {
            session.setConfig("StrictHostKeyChecking", "yes")
            failure = runCatching { session.connect(12000) }.exceptionOrNull()
        } finally { session.disconnect() }
        return found ?: throw IllegalStateException("host key unavailable", failure)
    }

    private fun connect(host: String, port: Int, user: String, password: String, fingerprint: String): Session {
        val ssh = JSch()
        ssh.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                if (key == null) return HostKeyRepository.NOT_INCLUDED
                return if (MessageDigest.isEqual(fingerprint(key).toByteArray(), fingerprint.toByteArray())) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "fingerprint"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val session = ssh.getSession(user, host, port)
        try {
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password")
            session.connect(12000)
            return session
        } catch (e: Exception) { session.disconnect(); throw e }
    }

    fun test(host: String, port: Int, user: String, password: String, fingerprint: String): String {
        val session = connect(host, port, user, password, fingerprint)
        session.disconnect()
        return "SSH 连接成功；尚未部署云端任务"
    }

    suspend fun command(host: String, port: Int, user: String, password: String, fingerprint: String, command: String): CloudCommandResult {
        val session = connect(host, port, user, password, fingerprint)
        var channel: ChannelExec? = null
        val bytes = ByteArrayOutputStream()
        var truncated = false
        val sink = object : OutputStream() {
            override fun write(b: Int) = synchronized(bytes) {
                if (bytes.size() < 16384) bytes.write(b) else truncated = true
            }
        }
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setOutputStream(sink)
            channel.setErrStream(sink)
            channel.connect(12000)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!channel.isClosed && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                delay(100)
            }
            val timedOut = !channel.isClosed
            val output = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
            return CloudCommandResult(output, if (timedOut) null else channel.exitStatus, timedOut, truncated)
        } finally {
            channel?.disconnect()
            session.disconnect()
        }
    }
}
