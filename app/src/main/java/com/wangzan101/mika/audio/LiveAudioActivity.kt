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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.cap.phone.AsrConfig
import com.wangzan101.mika.core.cap.phone.AsrConfigText
import com.wangzan101.mika.core.cap.phone.AsrMismatch
import com.wangzan101.mika.core.cap.phone.AsrParsed
import com.wangzan101.mika.core.cap.phone.AsrPresets
import com.wangzan101.mika.core.cap.phone.AsrStored
import com.wangzan101.mika.core.cap.phone.EndpointProblem
import com.wangzan101.mika.settings.FeatureFlags
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.*

/** Visible setup and explicit Android permission grant; no background permission prompts. */
class LiveAudioActivity : Activity() {
    private lateinit var settings: LiveAudioSettings
    private lateinit var provider: Spinner
    private lateinit var paste: EditText
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var mic: Switch
    private lateinit var warning: TextView
    private lateinit var status: TextView
    private val diagnostics = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var debug: TextView? = null
    private var ready = false
    private var updating = false
    /** User picked 自定义, so a key prefix must not overwrite the URL and model. */
    private var userChoseCustom = false
    /** User changed the key field. A blank field that was never edited must not clear the stored key. */
    private var keyEdited = false
    private val refresh = object : Runnable {
        override fun run() {
            status.text = listOf(LiveAudioSession.status, LiveAudioSession.preview()).filter { it.isNotBlank() }.joinToString("\n")
            debug?.text = "调试 session ${LiveAudioSession.generation} · ${LiveAudioSession.source} · peak ${LiveAudioSession.lastPeak} · rms ${LiveAudioSession.lastRms}"
            handler.postDelayed(this, 1000)
        }
    }
    private val persistSoon = Runnable { writeSettings(commit = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LiveAudioSettings(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fun text(value: String) = TextView(this).also { it.text = value; it.setPadding(0, 12, 0, 12); layout.addView(it) }
        fun field(hint: String, value: String, singleLine: Boolean = true) = EditText(this).also {
            it.hint = hint
            it.setText(value)
            if (singleLine) it.setSingleLine() else { it.minLines = 2; it.maxLines = 4 }
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.setAutofillHints()
            layout.addView(it)
        }
        text("直播监听与自动回复").textSize = 22f
        text("持续采集，每约 8 秒识别一段。音频发送到下面的语音识别服务，转写交给当前聊天模型。只有聊天模型支持工具调用才能操作回复；语音接口另行计费。")
        text("选 OpenRouter 或 OpenAI 会填好地址和模型，只需粘贴 Key。以 sk-or- 开头的 Key 会自动选 OpenRouter。地址、模型和 Key 会在输入时保存，离开页面也不会丢。也可以一次性粘贴。").textSize = 14f
        text("服务商")
        provider = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("OpenRouter", "OpenAI", "自定义")).also { adapter ->
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(it)
        }
        paste = field("一次性粘贴地址、模型和 Key，或只粘贴 Key", "", singleLine = false)
        endpoint = field("完整语音识别接口地址（HTTPS）", settings.endpoint)
        model = field("语音识别模型", settings.model)
        key = field("语音识别 API Key", settings.key).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setAutofillHints()
        }
        warning = text("").apply { setTextColor(0xFFB3261E.toInt()); visibility = View.GONE }
        mic = Switch(this).apply { text = "使用麦克风（需要直播外放，会收录周围声音）"; isChecked = settings.microphone; layout.addView(this) }
        text("默认采集抖音内部音频。连续约 24 秒无声时，只这一场自动改用麦克风外放，不会改上面的开关。下次开始仍按开关；要一直用麦克风请自己打开再保存。麦克风也无声则只提示检查外放，不会来回切换。监听期间请关闭「被操作的 App 静音」。华为等机型可能拦截内部采集。内部采集不录制屏幕画面。")
        status = text(LiveAudioSession.status)
        if (BuildConfig.DEBUG) debug = text("")
        val diagnosis = text("可先测试接口，再开启录音。测试会发送内置短语音，按语音服务计费，不使用麦克风。")
        diagnosis.setTextIsSelectable(true)
        val test = Button(this).apply { text = "测试语音识别接口（不录音）"; layout.addView(this) }
        val save = Button(this).apply { text = "保存"; layout.addView(this) }
        val start = Button(this).apply { text = "开始监听"; layout.addView(this) }
        Button(this).apply { text = "停止监听"; setOnClickListener { stopService(Intent(this@LiveAudioActivity, LiveAudioService::class.java)) }; layout.addView(this) }
        text("开始后回到聊天，说：进入指定直播间，持续听主播讲话，有合适的话题再回复；不要重复，每条至少间隔 15 秒，直到我停止。\n通知栏可随时停止监听。单次最长一小时。聊天任务还没接上时，不会因两分钟空闲停止；接上后两分钟没有读取会自动停止。在本页重新开始后，回到原来的聊天再让它听即可。")
        Button(this).apply { text = "返回"; setOnClickListener { finish() }; layout.addView(this) }
        val scroll = ScrollView(this).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(layout)
        }
        setContentView(scroll)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        updating = true
        provider.setSelection(presetIndex(settings.endpoint, settings.model))
        updating = false
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!ready || updating) return
                when (position) {
                    0 -> applyPreset(AsrPresets.openRouter)
                    1 -> applyPreset(AsrPresets.openAi)
                    else -> userChoseCustom = true
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        paste.onUserText { inserted ->
            val text = paste.text.toString()
            if (inserted <= 1 && '\n' !in text) return@onUserText
            val parsed = AsrConfigText.parse(text)
            if (!parsed.hasAny()) {
                if (inserted > 8) toast("没有识别出地址、模型或 Key")
                return@onUserText
            }
            applyParsed(parsed)
            updating = true
            paste.setText("")
            updating = false
            toast("已填入")
        }
        endpoint.onUserText { inserted -> onEndpointOrModel(endpoint, inserted) }
        model.onUserText { inserted -> onEndpointOrModel(model, inserted) }
        key.onUserText { inserted ->
            // A system clear of the password field is not an edit, so it must not wipe the stored key.
            if (inserted > 0 || key.isFocused) keyEdited = true
            val text = key.text.toString()
            if ((inserted > 1 || '\n' in text) && AsrConfigText.looksLikeBundle(text)) {
                applyParsed(AsrConfigText.parse(text))
                return@onUserText
            }
            val suggested = AsrPresets.forKey(text)
            val current = AsrPresets.matching(endpoint.text.toString(), model.text.toString())
            if (suggested != null && !userChoseCustom && (endpoint.text.isBlank() || current != null) && current?.id != suggested.id) {
                applyPreset(suggested)
                return@onUserText
            }
            refreshWarning()
            schedulePersist()
        }
        mic.setOnCheckedChangeListener { _, _ -> if (ready && !updating) schedulePersist() }
        test.setOnClickListener {
            val fields = validatedFields() ?: return@setOnClickListener
            confirmMatch(fields.first, fields.third) {
                writeSettings(commit = true)
                test.isEnabled = false
                diagnosis.text = "正在测试 ${fields.first.toHttpUrlOrNull()?.host ?: "接口"}，最长可能需要约 80 秒…"
                diagnostics.launch {
                    try {
                        val transcript = withContext(Dispatchers.IO) {
                            val pcm = assets.open("audio/connection-check.pcm").use { it.readBytes() }
                            LiveTranscriber(fields.first, fields.third, fields.second).transcribe(pcm)
                        }
                        diagnosis.text = if (transcript.isBlank()) "接口已响应，但测试语音未识别出文字，请检查语音模型。"
                            else "语音接口正常。测试识别：$transcript\n若直播仍无文字，请检查收音方式、静音开关和权限。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { diagnosis.text = "接口测试失败：${e.message ?: "未知错误"}" }
                    finally { test.isEnabled = true }
                }
            }
        }
        save.setOnClickListener {
            val wrote = writeSettings(commit = true)
            if (!wrote) { toast("配置保存失败"); return@setOnClickListener }
            val problem = AsrConfig.endpointProblem(endpoint.text.toString().trim())
            val mismatch = AsrConfig.mismatch(endpoint.text.toString(), key.text.toString())
            toast(when {
                endpoint.text.isNotBlank() && problem != null -> "已保存，但${problemZh(problem)}"
                model.text.isBlank() -> "已保存。请填写语音识别模型"
                key.text.isBlank() && keyEdited -> "已保存。已清除 API Key"
                key.text.isBlank() -> "已保存。尚未填写 API Key"
                mismatch != null -> "已保存。接口和 Key 可能不是同一家，开始前会再提醒"
                else -> "已保存"
            })
        }
        start.setOnClickListener {
            if (LiveAudioSession.running) { toast("请先停止当前监听"); return@setOnClickListener }
            val fields = validatedFields() ?: return@setOnClickListener
            confirmMatch(fields.first, fields.third) {
                val wrote = runCatching { settings.save(fields.first, fields.second, mic.isChecked, fields.third, commit = true) }
                if (wrote.isFailure) { toast("配置保存失败"); return@confirmMatch }
                if (FeatureFlags.get(this@LiveAudioActivity).muteTarget()) {
                    android.app.AlertDialog.Builder(this@LiveAudioActivity).setTitle("关闭任务 App 静音？")
                        .setMessage("监听需要抖音出声。将关闭「被操作的 App 静音」。若任务正在运行，请停止任务后重新进入直播间。")
                        .setPositiveButton("关闭并继续") { _, _ -> FeatureFlags.get(this@LiveAudioActivity).setMuteTarget(false); authorize() }
                        .setNegativeButton("取消", null).show()
                } else authorize()
            }
        }
        ready = true
        refreshWarning()
    }

    private fun presetIndex(url: String, name: String): Int = when (AsrPresets.matching(url, name)?.id) {
        "openrouter" -> 0
        "openai" -> 1
        else -> 2
    }

    private fun applyPreset(preset: AsrPresets.Preset) {
        userChoseCustom = false
        updating = true
        endpoint.setText(preset.endpoint)
        model.setText(preset.model)
        provider.setSelection(if (preset.id == "openrouter") 0 else 1)
        updating = false
        refreshWarning()
        schedulePersist()
    }

    private fun applyParsed(parsed: AsrParsed) {
        val lockCustom = parsed.presetId == "custom" || (userChoseCustom && parsed.presetId == null && parsed.endpoint == null)
        val merged = AsrConfig.merge(
            AsrStored(endpoint.text.toString(), model.text.toString(), key.text.toString(), mic.isChecked),
            parsed,
            lockCustom,
        )
        if (parsed.key != null) keyEdited = true
        userChoseCustom = parsed.presetId == "custom" || AsrPresets.matching(merged.endpoint, merged.model) == null
        updating = true
        endpoint.setText(merged.endpoint)
        model.setText(merged.model)
        key.setText(merged.key)
        provider.setSelection(if (userChoseCustom) 2 else presetIndex(merged.endpoint, merged.model))
        updating = false
        refreshWarning()
        schedulePersist()
    }

    private fun onEndpointOrModel(source: EditText, inserted: Int) {
        val text = source.text.toString()
        if ((inserted > 1 || '\n' in text) && AsrConfigText.looksLikeBundle(text)) {
            applyParsed(AsrConfigText.parse(text))
            return
        }
        val match = AsrPresets.matching(endpoint.text.toString(), model.text.toString())
        if (match == null) userChoseCustom = true
        val index = if (userChoseCustom || match == null) 2 else presetIndex(endpoint.text.toString(), model.text.toString())
        if (provider.selectedItemPosition != index) {
            updating = true
            provider.setSelection(index)
            updating = false
        }
        refreshWarning()
        schedulePersist()
    }

    private fun refreshWarning() {
        val mismatch = AsrConfig.mismatch(endpoint.text.toString(), key.text.toString())
        if (mismatch == null) {
            warning.text = ""
            warning.visibility = View.GONE
        } else {
            warning.visibility = View.VISIBLE
            warning.text = mismatchZh(mismatch)
        }
    }

    private fun validatedFields(): Triple<String, String, String>? {
        val url = endpoint.text.toString().trim()
        val problem = AsrConfig.endpointProblem(url)
        if (problem != null) { toast(problemZh(problem)); return null }
        val modelName = model.text.toString().trim()
        val apiKey = key.text.toString().trim()
        if (apiKey.isBlank() || modelName.isBlank()) { toast("请填写语音识别 API Key 和模型"); return null }
        return Triple(url, modelName, apiKey)
    }

    private fun confirmMatch(url: String, apiKey: String, then: () -> Unit) {
        val mismatch = AsrConfig.mismatch(url, apiKey) ?: run { then(); return }
        val preset = AsrPresets.forKey(apiKey)
        android.app.AlertDialog.Builder(this)
            .setTitle("接口和 Key 可能不匹配")
            .setMessage(mismatchZh(mismatch) + "\n继续调用可能会返回 HTTP 401。")
            .setPositiveButton(if (preset?.id == "openrouter") "改用 OpenRouter" else "改用 OpenAI") { _, _ ->
                preset?.let { applyPreset(it) }
                toast("已改成匹配的预设，请再点一次")
            }
            .setNeutralButton("仍然继续") { _, _ -> then() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mismatchZh(mismatch: AsrMismatch): String = when (mismatch.keyKind) {
        "openrouter" -> "这个 Key 以 sk-or- 开头，属于 OpenRouter，但当前接口是 OpenAI。"
        else -> "这个 Key 看起来属于 OpenAI，但当前接口是 OpenRouter。"
    }

    private fun problemZh(problem: EndpointProblem): String = when (problem) {
        EndpointProblem.Empty -> "请填写语音识别接口地址"
        EndpointProblem.NotUrl, EndpointProblem.NotHttps -> "请填写有效的 HTTPS 语音识别接口地址"
        EndpointProblem.UserInfo -> "请输入不含用户名密码的 HTTPS 语音识别接口地址"
        EndpointProblem.SecretQuery -> "请把 API Key 填在 Key 栏，不要写在地址里"
    }

    private fun schedulePersist() {
        if (!ready) return
        handler.removeCallbacks(persistSoon)
        handler.postDelayed(persistSoon, 300)
    }

    private fun writeSettings(commit: Boolean, allowClear: Boolean = keyEdited): Boolean = runCatching {
        val keyArg = when {
            key.text.isNotBlank() -> key.text.toString()
            allowClear -> ""
            else -> null
        }
        settings.save(endpoint.text.toString(), model.text.toString(), mic.isChecked, keyArg, commit)
    }.isSuccess

    private fun EditText.onUserText(onChange: (inserted: Int) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            private var inserted = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { inserted = count }
            override fun afterTextChanged(s: Editable?) {
                if (!ready || updating) return
                onChange(inserted)
            }
        })
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
    override fun onPause() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(persistSoon)
        if (ready) writeSettings(commit = true)
        super.onPause()
    }
    override fun onDestroy() { diagnostics.cancel(); super.onDestroy() }
}
