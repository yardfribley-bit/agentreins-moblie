package com.wangzan101.mika.relay

import android.content.Context
import com.wangzan101.mika.core.relay.Fetched
import com.wangzan101.mika.core.relay.HttpsUrls
import com.wangzan101.mika.core.relay.HUB_ATTACHMENT_MAX_BYTES
import com.wangzan101.mika.core.relay.UrlDownloadPort
import com.wangzan101.mika.core.relay.UrlDownloadResult
import com.wangzan101.mika.core.relay.UrlFetcher
import com.wangzan101.mika.core.relay.UrlWorkspaceDownload
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 把 https 链接下到这次对话的工作区，图片和视频再进相册。 */
class PhoneUrlDownload(private val context: Context, private val conversationId: String) : UrlDownloadPort {
    override fun download(url: String): UrlDownloadResult = UrlWorkspaceDownload(
        fetch = Fetcher,
        write = { name, bytes -> HubInboxWriter(context, conversationId).write(name, bytes) },
        gallery = MediaStoreGallery(context),
    ).download(url)

    private object Fetcher : UrlFetcher {
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        override fun fetch(url: String, maxBytes: Long): Fetched {
            val rejected = HttpsUrls.reject(url)
            if (rejected != null) throw java.io.IOException(rejected)
            var current = url
            val cap = maxBytes.coerceAtMost(HUB_ATTACHMENT_MAX_BYTES)
            repeat(4) { hop ->
                val request = Request.Builder().url(current).header("Accept", "*/*").get().build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code in 300..399) {
                        if (hop == 3) throw java.io.IOException("too many redirects")
                        val loc = resp.header("Location") ?: throw java.io.IOException("bad redirect")
                        val next = resp.request.url.resolve(loc)?.toString() ?: throw java.io.IOException("bad redirect")
                        if (HttpsUrls.reject(next) != null) throw java.io.IOException("https only")
                        current = next
                        return@use
                    }
                    if (!resp.isSuccessful) throw java.io.IOException("download failed")
                    val body = resp.body ?: throw java.io.IOException("empty file")
                    if (body.contentLength() > cap) throw java.io.IOException("larger than ${cap shr 20} MB")
                    val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size().toLong() + n > cap) throw java.io.IOException("larger than ${cap shr 20} MB")
                            out.write(buf, 0, n)
                        }
                    }
                    val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    return Fetched(out.toByteArray(), mime, HttpsUrls.leaf(url))
                }
            }
            throw java.io.IOException("too many redirects")
        }
    }
}
