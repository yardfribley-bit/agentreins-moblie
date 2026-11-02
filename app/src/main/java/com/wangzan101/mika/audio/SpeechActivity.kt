package com.wangzan101.mika.audio

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.wangzan101.mika.core.cap.phone.AsrConfig
import com.wangzan101.mika.core.cap.phone.speech.SpeechConfig
import com.wangzan101.mika.core.cap.phone.speech.SpeechPresets
import com.wangzan101.mika.core.cap.phone.speech.SpeechRoute
import com.wangzan101.mika.core.cap.phone.speech.SpeechStored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Speech settings. Edits are written as they change, the same way live listening saves. */
class SpeechActivity : Activity() {
    private lateinit var settings: SpeechSettings
    private lateinit var allow: Switch
    private lateinit var preset: Spinner
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var voice: Spinner
    private lateinit var key: EditText
    private lateinit var rateLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var rateBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var route: Spinner
    private lateinit var status: TextView
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var updating = false
    private var keyEdited = false
    private val voices = listOf("default") + SpeechPresets.miniVoices
    private val persistSoon = Runnable { write(commit = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SpeechSettings(this)
        val stored = settings.snapshot()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fun text(value: String) = TextView(this).also { it.text = value; it.setPadding(0, 12, 0, 12); layout.addView(it) }
        fun field(hint: String, value: String) = EditText(this).also {
            it.hint = hint
            it.setText(value)
            it.setSingleLine()
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(it)
        }
        text("直播出声").textSize = 22f
        text("把回复说出来，让直播间的麦克风听见。默认关闭。上麦前会再问一次。试听不打开开关。").textSize = 14f
        allow = Switch(this).apply { text = "允许出声"; isChecked = stored.allow; layout.addView(this) }
        text("预设")
        preset = spinner(listOf("OpenRouter 自然", "OpenAI 自然", "OpenAI 低延迟", "系统语音", "自定义"), layout)
        endpoint = field("语音合成 HTTPS 地址", stored.endpoint)
        model = field("语音模型", stored.model)
        text("音色")
        voice = spinner(voices, layout)
        key = field("语音 API Key", stored.key).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        rateLabel = text(rateText(stored.rate))
        rateBar = SeekBar(this).apply { max = 15; progress = ((stored.rate - 0.5f) * 10).toInt().coerceIn(0, 15); layout.addView(this) }
        volumeLabel = text(volumeText(stored.volume))
        volumeBar = SeekBar(this).apply { max = 100; progress = (stored.volume * 100).toInt().coerceIn(0, 100); layout.addView(this) }
        text("播放路线")
        route = spinner(listOf("扬声器回授", "外接声卡（尚未接入）"), layout)
        status = text("").apply { setTextIsSelectable(true) }
        val listen = Button(this).apply { text = "试听"; layout.addView(this) }
        Button(this).apply { text = "返回"; setOnClickListener { finish() }; layout.addView(this) }
        text("在线预设走 OpenAI 兼容的 /audio/speech，按句分段，第一句先出声。系统语音不联网，中文音色取决于这台手机是否已安装，听感可能偏机械。外放用无障碍辅助音量，并优先内置扬声器，不改通话模式。")
        setContentView(ScrollView(this).apply { addView(layout) })

        updating = true
        preset.setSelection(presetIndex(stored))
        val voiceAt = voices.indexOf(stored.voice).let { if (it < 0) 0 else it }
        voice.setSelection(voiceAt)
        route.setSelection(if (stored.route == SpeechRoute.EXTERNAL) 1 else 0)
        updating = false

        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!ready || updating) return
                val chosen = when (position) {
                    0 -> SpeechPresets.openRouter
                    1 -> SpeechPresets.openAi
                    2 -> SpeechPresets.openAiFast
                    3 -> SpeechPresets.system
                    else -> null
                }
                if (chosen != null) applyPreset(chosen)
                else schedule()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        allow.setOnCheckedChangeListener { _, _ -> if (ready && !updating) schedule() }
        endpoint.onUserText { schedule() }
        model.onUserText { schedule() }
        key.onUserText { inserted ->
            if (inserted > 0 || key.isFocused) keyEdited = true
            schedule()
        }
        voice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (ready && !updating) schedule()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        rateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rateLabel.text = rateText(rate())
                if (fromUser) schedule()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = volumeText(volume())
                if (fromUser) schedule()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        route.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (ready && !updating) schedule()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        listen.setOnClickListener {
            if (!write(commit = true)) { toast("配置保存失败"); return@setOnClickListener }
            val current = settings.snapshot()
            if (current.provider == "online") {
                val problem = AsrConfig.endpointProblem(current.endpoint)
                if (problem != null || current.model.isBlank() || current.key.isBlank() || current.voice.isBlank()) {
                    toast("请先填好地址、模型、音色和 Key")
                    return@setOnClickListener
                }
            }
            if (current.route == SpeechRoute.EXTERNAL) {
                status.text = "外接声卡还没接入，试听请先选扬声器回授。"
                return@setOnClickListener
            }
            listen.isEnabled = false
            status.text = "正在合成试听…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { SpeechRuntime.ensure(this@SpeechActivity).preview("你好，我在试听这句中文。") }
                val message = result["message"]?.toString()?.trim('"') ?: result.toString()
                val first = result["firstAudioMs"]?.toString()
                status.text = if (first != null) "$message\n首包约 ${first.trim('"')} 毫秒（从开始请求到第一段写入扬声器）。" else message
                listen.isEnabled = true
            }
        }
        ready = true
    }

    private fun spinner(items: List<String>, layout: LinearLayout) = Spinner(this).also {
        it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layout.addView(it)
    }

    private fun applyPreset(chosen: SpeechPresets.Preset) {
        updating = true
        if (chosen.provider == "online") {
            endpoint.setText(chosen.endpoint)
            model.setText(chosen.model)
            val at = voices.indexOf(chosen.voice)
            if (at >= 0) voice.setSelection(at)
        } else {
            val at = voices.indexOf("default")
            if (at >= 0) voice.setSelection(at)
        }
        preset.setSelection(presetIndex(currentStored()))
        updating = false
        schedule()
    }

    private fun presetIndex(stored: SpeechStored): Int = when (SpeechConfig.publish(stored).preset) {
        "openrouter" -> 0
        "openai" -> 1
        "openai-fast" -> 2
        "system" -> 3
        else -> 4
    }

    private fun rate() = 0.5f + rateBar.progress / 10f
    private fun volume() = volumeBar.progress / 100f
    private fun rateText(value: Float) = "语速 ${"%.1f".format(value)}（0.5 慢，1 正常，2 快）"
    private fun volumeText(value: Float) = "音量 ${(value * 100).toInt()}%"

    private fun currentStored(): SpeechStored {
        val system = preset.selectedItemPosition == 3
        val voiceName = voices.getOrElse(voice.selectedItemPosition) { "coral" }
        return SpeechStored(
            allow = allow.isChecked,
            provider = if (system) "system" else "online",
            endpoint = if (system) "" else endpoint.text.toString().trim(),
            model = if (system) "" else model.text.toString().trim(),
            voice = if (system && voiceName !in SpeechPresets.miniVoices) voiceName else voiceName,
            key = key.text.toString(),
            rate = rate(),
            volume = volume(),
            route = if (route.selectedItemPosition == 1) SpeechRoute.EXTERNAL else SpeechRoute.SPEAKER,
        )
    }

    private fun schedule() {
        if (!ready) return
        handler.removeCallbacks(persistSoon)
        handler.postDelayed(persistSoon, 300)
    }

    private fun write(commit: Boolean): Boolean = runCatching {
        val stored = currentStored()
        val keyArg = when {
            key.text.isNotBlank() -> key.text.toString()
            keyEdited -> ""
            else -> null
        }
        settings.save(stored.copy(key = ""), keyArg, commit)
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

    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }

    override fun onPause() {
        handler.removeCallbacks(persistSoon)
        if (ready) write(commit = true)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
