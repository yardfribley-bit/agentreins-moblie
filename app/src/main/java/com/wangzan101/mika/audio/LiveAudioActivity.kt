package com.wangzan101.mika.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import com.wangzan101.mika.settings.FeatureFlags
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.*

/** Visible setup and explicit Android permission grant; no background permission prompts. */
class LiveAudioActivity : Activity() {
    private lateinit var settings: LiveAudioSettings
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var mic: Switch
    private lateinit var status: TextView
    private val diagnostics = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { status.text = listOf(LiveAudioSession.status, LiveAudioSession.preview()).filter { it.isNotBlank() }.joinToString("\n"); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LiveAudioSettings(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 36, 32, 24) }
        fun text(value: String) = TextView(this).also { it.text = value; it.setPadding(0, 12, 0, 12); layout.addView(it) }
        fun field(hint: String, value: String) = EditText(this).also { it.hint = hint; it.setText(value); it.setSingleLine(); layout.addView(it) }
        text("直播监听与自动回复").textSize = 22f
        text("持续采集，每约 8 秒识别一段。音频发送到下面的语音识别服务，转写交给当前聊天模型。只有聊天模型支持工具调用才能操作回复；语音接口另行计费。")
        endpoint = field("完整语音识别接口地址（HTTPS）", settings.endpoint)
        model = field("语音识别模型", settings.model)
        key = field("语音识别 API Key", settings.key).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        mic = Switch(this).apply { text = "使用麦克风（需要直播外放，会收录周围声音）"; isChecked = settings.microphone; layout.addView(this) }
        text("默认仅采集抖音 App 内部音频。若抖音或系统禁止采集，可能只能录到静音，可停止后尝试麦克风外放。监听期间请关闭「被操作的 App 静音」。内部采集不录制屏幕画面。")
        status = text(LiveAudioSession.status)
        val diagnosis = text("可先测试接口，再开启录音。测试会发送内置短语音，按语音服务计费，不使用麦克风。")
        diagnosis.setTextIsSelectable(true)
        Button(this).apply {
            text = "测试语音识别接口（不录音）"
            setOnClickListener {
                val url = endpoint.text.toString().trim().toHttpUrlOrNull()
                if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                    toast("请填写有效的 HTTPS 语音识别接口地址"); return@setOnClickListener
                }
                val apiKey = key.text.toString().trim()
                val modelName = model.text.toString().trim()
                if (apiKey.isBlank() || modelName.isBlank()) { toast("请填写语音识别 Key 和模型"); return@setOnClickListener }
                isEnabled = false
                diagnosis.text = "正在测试 ${url.host}，最长可能需要约 80 秒…"
                diagnostics.launch {
                    try {
                        val transcript = withContext(Dispatchers.IO) {
                            val pcm = assets.open("audio/connection-check.pcm").use { it.readBytes() }
                            LiveTranscriber(url.toString(), apiKey, modelName).transcribe(pcm)
                        }
                        diagnosis.text = if (transcript.isBlank()) "接口已响应，但测试语音未识别出文字，请检查语音模型。"
                            else "语音接口正常。测试识别：$transcript\n若直播仍无文字，请检查收音方式、静音开关和权限。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { diagnosis.text = "接口测试失败：${e.message ?: "未知错误"}" }
                    finally { isEnabled = true }
                }
            }
            layout.addView(this)
        }
        Button(this).apply {
            text = "保存并开始监听"
            setOnClickListener {
                if (LiveAudioSession.running) { toast("请先停止当前监听"); return@setOnClickListener }
                val url = endpoint.text.toString().trim().toHttpUrlOrNull()
                if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                    toast("请输入不含用户名密码的 HTTPS 语音识别接口地址"); return@setOnClickListener
                }
                if (key.text.isBlank() || model.text.isBlank()) { toast("请填写语音识别 API Key 和模型"); return@setOnClickListener }
                runCatching {
                    settings.endpoint = url.toString(); settings.model = model.text.toString().trim()
                    settings.key = key.text.toString().trim(); settings.microphone = mic.isChecked
                }.onFailure { toast("配置保存失败"); return@setOnClickListener }
                if (FeatureFlags.get(this@LiveAudioActivity).muteTarget()) {
                    android.app.AlertDialog.Builder(this@LiveAudioActivity).setTitle("关闭任务 App 静音？")
                        .setMessage("监听需要抖音出声。将关闭「被操作的 App 静音」。若任务正在运行，请停止任务后重新进入直播间。")
                        .setPositiveButton("关闭并继续") { _, _ -> FeatureFlags.get(this@LiveAudioActivity).setMuteTarget(false); authorize() }
                        .setNegativeButton("取消", null).show()
                } else authorize()
            }
            layout.addView(this)
        }
        Button(this).apply { text = "停止监听"; setOnClickListener { stopService(Intent(this@LiveAudioActivity, LiveAudioService::class.java)) }; layout.addView(this) }
        text("开始后回到聊天，说：进入指定直播间，持续听主播讲话，有合适的话题再回复；不要重复，每条至少间隔 15 秒，直到我停止。\n通知栏可随时停止监听。单次最长一小时，两分钟无任务读取会自动停止。")
        Button(this).apply { text = "返回"; setOnClickListener { finish() }; layout.addView(this) }
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun authorize() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31); return
        }
        if (settings.microphone) start(null) else {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            startActivityForResult(intent, 32)
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 31 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) authorize() else toast("未授权，监听未启动")
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 32 && result == RESULT_OK && data != null) start(data) else toast("已取消采集授权")
    }
    private fun start(grant: Intent?) {
        try {
            startForegroundService(Intent(this, LiveAudioService::class.java).putExtra("microphone", settings.microphone).apply {
                grant?.let { putExtra("projection", it) }
            })
        } catch (e: Exception) { toast("启动失败，请保持本页面在前台重试") }
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { diagnostics.cancel(); super.onDestroy() }
}
