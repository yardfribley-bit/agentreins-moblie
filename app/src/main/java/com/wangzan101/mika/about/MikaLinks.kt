package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** Mika 对外的地址都在这里:仓库、问题、作者、检查更新用的两个来源。换地址只改这一处。 */
object MikaLinks {
    const val SITE = "https://www.agentreins.info"
    const val REPO = "https://github.com/yardfribley-bit/agentreins-moblie"
    const val REPO_LABEL = "github.com/yardfribley-bit/agentreins-moblie"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/agentreins"
    const val X_HANDLE = "@agentreins"

    /** 检查更新先问 GitHub 的 Latest。没有正式版时改问 [RELEASES_API]（含预发布）。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/yardfribley-bit/agentreins-moblie/releases/latest"
    const val RELEASES_API = "https://api.github.com/repos/yardfribley-bit/agentreins-moblie/releases?per_page=20"

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、Mika 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
