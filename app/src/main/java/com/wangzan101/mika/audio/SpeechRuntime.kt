package com.wangzan101.mika.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.wangzan101.mika.core.cap.phone.speech.Floor
import com.wangzan101.mika.core.cap.phone.speech.FloorEvent
import com.wangzan101.mika.core.cap.phone.speech.FloorSession
import com.wangzan101.mika.core.cap.phone.speech.MicSeatMachine
import com.wangzan101.mika.core.cap.phone.speech.MicSeatPort
import com.wangzan101.mika.core.cap.phone.speech.OnMicHearing
import com.wangzan101.mika.core.cap.phone.speech.Seat
import com.wangzan101.mika.core.cap.phone.speech.SpeechChunker
import com.wangzan101.mika.core.cap.phone.speech.SpeechControl
import com.wangzan101.mika.core.cap.phone.speech.SpeechFloor
import com.wangzan101.mika.core.cap.phone.speech.SpeechPort
import com.wangzan101.mika.core.cap.phone.speech.SpeechRoute
import com.wangzan101.mika.core.cap.phone.speech.SpeechStored
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide speaker, mic-seat consent, and the 正在出声 indicator. */
object SpeechRuntime {
    @Volatile private var installed: PhoneSpeech? = null

    fun ensure(context: Context): PhoneSpeech =
        installed ?: synchronized(this) {
            installed ?: PhoneSpeech(context.applicationContext).also { installed = it }
        }

    fun tapStop() { installed?.tapStop() }
}

class PhoneSpeech(private val app: Context) : SpeechPort, MicSeatPort {
    private val settings = SpeechSettings(app)
    private val seat = MicSeatMachine()
    private val floor = FloorSession()
    private val mutex = Mutex()
    private val stopFlag = AtomicBoolean(false)
    private val yieldFlag = AtomicBoolean(false)
    private val releaseOnStop = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var engineStarting = false
    private val engineReady = CompletableDeferred<Boolean>()
    @Volatile private var client: OnlineSpeechClient? = null
    @Volatile private var track: SpeakerTrack? = null
    @Volatile var lastFirstAudioMs: Long? = null
        private set

    init {
        SpeechControl.onOwnerStop = { requestStop(releaseMic = false) }
        SpeechControl.onYield = { requestYield() }
    }

    override suspend fun speak(text: String): JsonObject = say(text, preview = false)

    suspend fun preview(text: String): JsonObject = say(text, preview = true)

    private suspend fun say(text: String, preview: Boolean): JsonObject = mutex.withLock {
        val stored = settings.snapshot()
        if (!preview && !stored.allow) {
            return@withLock fail("speech_disabled", "允许出声 is off. The owner turns it on in 设置 → 干活时 → 直播出声. Do not turn it on yourself.")
        }
        if (!SpeechRoute.profile(stored.route).available) {
            return@withLock fail("route_unavailable", "The external sound-card route is not connected. Use the speaker loopback, or connect a route later.")
        }
        if (stored.provider == "online" && stored.key.isBlank()) {
            return@withLock fail("speech_key_missing", "No speech API key is saved. Add it in 设置 → 干活时 → 直播出声, or switch to the system voice.")
        }
        val chunks = SpeechChunker.chunks(text)
        if (chunks.isEmpty()) return@withLock fail("empty_text", "Nothing to say.")
        stopFlag.set(false)
        yieldFlag.set(false)
        releaseOnStop.set(false)
        synchronized(floor) { floor.on(FloorEvent.Start) }
        SpeechFloor.beginUtterance(text, System.currentTimeMillis())
        SpeakingIndicator.show(app) { tapStop() }
        var status = "finished"
        var detail: String? = null
        var firstMs: Long? = null
        try {
            val played = if (stored.provider == "system") speakSystem(stored, chunks) else speakOnline(stored, chunks)
            status = played.status
            detail = played.detail
            firstMs = played.firstAudioMs
            lastFirstAudioMs = firstMs
        } catch (e: kotlinx.coroutines.CancellationException) {
            requestStop(releaseMic = false)
            throw e
        } finally {
            track?.release()
            track = null
            SpeechFloor.endUtterance(System.currentTimeMillis())
            if (releaseOnStop.get()) releaseSeat()
            synchronized(floor) { if (floor.floor != Floor.Idle) floor.on(FloorEvent.Finished) }
            SpeakingIndicator.hide(app)
        }
        result(status, detail, firstMs, stored)
    }

    override fun stop(releaseMic: Boolean): JsonObject {
        requestStop(releaseMic)
        if (releaseMic) releaseSeat()
        SpeakingIndicator.hide(app)
        return buildJsonObject {
            put("ok", true)
            put("status", "stopped")
            put("releaseMic", releaseMic)
            put("micSeat", seat.seat.name.lowercase())
            put("continueListening", false)
            put("message", if (releaseMic) "Stopped and the mic seat was released." else "Stopped speaking. The mic seat is unchanged.")
        }
    }

    fun tapStop() {
        requestStop(releaseMic = true)
        releaseSeat()
        SpeakingIndicator.hide(app)
    }

    override fun status(): JsonObject = buildJsonObject {
        put("ok", true)
        put("speaking", SpeechFloor.speaking)
        put("micSeat", seat.seat.name.lowercase())
        put("allow", settings.snapshot().allow)
    }

    override suspend fun request(): JsonObject {
        val stored = settings.snapshot()
        val asked = synchronized(seat) { seat.request(stored.allow) }
        if (asked.code != "confirmation_required") return asked.toJson()
        val approved = MicConsent.ask(app)
        val answered = synchronized(seat) { seat.ownerAnswer(approved) }
        syncSeat()
        return answered.toJson()
    }

    override fun markHeld(): JsonObject {
        val result = synchronized(seat) { seat.markHeld() }
        syncSeat()
        return result.toJson()
    }

    override fun release(): JsonObject {
        val result = releaseSeat()
        return result.toJson()
    }

    override fun view(): JsonObject = synchronized(seat) { seatView() }

    private fun releaseSeat() = synchronized(seat) {
        val result = seat.release()
        syncSeat()
        result
    }

    private fun syncSeat() {
        SpeechFloor.seatHeld = seat.seat == Seat.Held
    }

    private fun requestStop(releaseMic: Boolean) {
        if (releaseMic) releaseOnStop.set(true)
        stopFlag.set(true)
        client?.cancel()
        track?.stopNow()
        main.post { engine?.stop() }
        synchronized(floor) {
            if (floor.floor == Floor.Speaking || floor.floor == Floor.Yielded) {
                floor.on(if (releaseMic) FloorEvent.TapStop else FloorEvent.OwnerStop)
            }
        }
    }

    private fun requestYield() {
        if (!SpeechFloor.speaking) return
        yieldFlag.set(true)
        client?.cancel()
        track?.stopNow()
        main.post { engine?.stop() }
        synchronized(floor) {
            if (floor.floor == Floor.Speaking) floor.on(FloorEvent.OthersTalking)
        }
    }

    private data class Played(val status: String, val detail: String?, val firstAudioMs: Long?)

    private fun speakOnline(stored: SpeechStored, chunks: List<String>): Played {
        val http = OnlineSpeechClient(stored.endpoint, stored.key, stored.model, stored.voice)
        client = http
        val player = SpeakerTrack(app, stored.volume)
        track = player
        var style = true
        return try {
            for (chunk in chunks) {
                if (stopFlag.get()) return stoppedOr("stopped")
                if (yieldFlag.get()) return stoppedOr("yielded")
                var attempt = 0
                while (true) {
                    try {
                        http.stream(chunk, stored.rate, style, { stopFlag.get() || yieldFlag.get() }) { pcm ->
                            if (stopFlag.get() || yieldFlag.get()) return@stream
                            player.write(pcm)
                        }
                        break
                    } catch (e: SpeechHttp) {
                        if (style && e.code == 400 && attempt == 0) {
                            style = false
                            attempt++
                            continue
                        }
                        return Played("error", e.message, player.firstAudioMs)
                    } catch (e: IOException) {
                        if (stopFlag.get() || yieldFlag.get()) break
                        return Played("error", "语音合成的网络中断了。", player.firstAudioMs)
                    }
                }
                if (stopFlag.get() || yieldFlag.get()) break
            }
            player.drain { stopFlag.get() || yieldFlag.get() }
            when {
                yieldFlag.get() -> stoppedOr("yielded")
                stopFlag.get() -> stoppedOr("stopped")
                else -> Played("finished", null, player.firstAudioMs)
            }
        } finally {
            client = null
        }
    }

    private fun stoppedOr(status: String) = Played(status, null, track?.firstAudioMs)

    private suspend fun speakSystem(stored: SpeechStored, chunks: List<String>): Played {
        val tts = awaitEngine() ?: return Played("error", "这台手机没有可用的系统语音引擎。", null)
        val started = System.currentTimeMillis()
        val done = CompletableDeferred<Played>()
        main.post {
            val ready = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (ready < TextToSpeech.LANG_AVAILABLE) {
                done.complete(Played("error", "系统语音没有中文音色。可以在系统设置里下载中文语音，或改用在线语音。", null))
                return@post
            }
            if (stored.voice.isNotBlank() && stored.voice != "default") {
                tts.voices?.firstOrNull { it.name == stored.voice }?.let { tts.voice = it }
            }
            tts.setSpeechRate(stored.rate)
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, stored.volume) }
            var first: Long? = null
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (first == null) first = System.currentTimeMillis() - started
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "s${chunks.lastIndex}" && !done.isCompleted) {
                        val status = when {
                            yieldFlag.get() -> "yielded"
                            stopFlag.get() -> "stopped"
                            else -> "finished"
                        }
                        done.complete(Played(status, null, first))
                    }
                }
                @Deprecated("legacy")
                override fun onError(utteranceId: String?) {
                    if (done.isCompleted) return
                    val status = when {
                        yieldFlag.get() -> "yielded"
                        stopFlag.get() -> "stopped"
                        else -> "error"
                    }
                    done.complete(Played(status, if (status == "error") "系统语音播放失败。" else null, first))
                }
            })
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts.speak(chunk, mode, params, "s$index")
            }
        }
        return withTimeoutOrNull(120_000) { done.await() } ?: Played("error", "系统语音超时。", null)
    }

    private suspend fun awaitEngine(): TextToSpeech? {
        if (!engineStarting) {
            engineStarting = true
            main.post {
                val created = TextToSpeech(app) { status -> engineReady.complete(status == TextToSpeech.SUCCESS) }
                engine = created
            }
        }
        return if (engineReady.await()) engine else null
    }

    private fun result(status: String, detail: String?, firstMs: Long?, stored: SpeechStored): JsonObject {
        val hearing = OnMicHearing.advise(
            SpeechFloor.seatHeld, SpeechFloor.captureIsMic, SpeechFloor.silentStreak, SpeechFloor.lastPeak, SpeechFloor.clientSilenced,
        )
        val message = detail ?: when (status) {
            "finished" -> "Spoken. Call phone_listen_live and answer what comes next. Do not answer your own speech."
            "yielded" -> "Someone else started talking, so this reply stopped. Call phone_listen_live and wait."
            "stopped" -> if (releaseOnStop.get()) "Stopped. The mic seat was released. If the room still shows this phone on mic, leave it from the screen."
            else "Stopped speaking. The mic seat is unchanged."
            else -> detail ?: "Could not speak."
        }
        return buildJsonObject {
            put("ok", status == "finished" || status == "yielded" || status == "stopped")
            put("status", status)
            put("continueListening", status == "finished" || status == "yielded")
            put("message", message)
            put("micSeat", seat.seat.name.lowercase())
            put("route", stored.route)
            put("provider", stored.provider)
            firstMs?.let { put("firstAudioMs", it) }
            if (SpeechFloor.seatHeld || hearing.hearing != "not_on_mic") {
                put("hearing", hearing.hearing)
                hearing.fallback?.let { put("hearingFallback", it) }
            }
        }
    }

    private fun fail(code: String, message: String) = buildJsonObject {
        put("ok", false); put("error", code); put("status", code); put("message", message); put("continueListening", false)
    }

    private fun com.wangzan101.mika.core.cap.phone.speech.SeatResult.toJson() = buildJsonObject {
        put("ok", ok)
        code?.let { put("error", it) }
        put("seat", seat.name.lowercase())
        put("releaseUi", releaseUi)
        put("message", message)
        val hearing = OnMicHearing.advise(seat == Seat.Held, SpeechFloor.captureIsMic, SpeechFloor.silentStreak, SpeechFloor.lastPeak, SpeechFloor.clientSilenced)
        if (seat == Seat.Held) {
            put("hearing", hearing.hearing)
            put("hearingSummary", hearing.summary)
            hearing.fallback?.let { put("hearingFallback", it) }
        }
    }

    private fun seatView() = buildJsonObject {
        put("ok", true)
        put("seat", seat.seat.name.lowercase())
        put("allow", settings.snapshot().allow)
    }
}

/** Owner confirmation for taking a mic seat. The notification actions are the reliable path. */
object MicConsent {
    private val lock = Any()
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun ask(context: Context): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) {
            pending?.complete(false)
            pending = deferred
        }
        val app = context.applicationContext
        SpeakingIndicator.ask(app)
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(90_000) { deferred.await() } ?: false
            }
        } finally {
            synchronized(lock) { if (pending === deferred) pending = null }
            SpeakingIndicator.dismissAsk(app)
        }
    }

    fun answer(approved: Boolean) {
        synchronized(lock) {
            pending?.complete(approved)
            pending = null
        }
    }
}
