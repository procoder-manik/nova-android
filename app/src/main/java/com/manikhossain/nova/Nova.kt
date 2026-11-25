package com.manikhossain.nova

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Nova's brain on the phone: microphone, wake word ("Nova"), Gemini Live voice session, speaker, tools. */
@SuppressLint("StaticFieldLeak")
object Nova {
    private const val TAG = "Nova"
    val MODELS = listOf("gemini-2.5-flash-native-audio-preview-12-2025", "gemini-3.1-flash-live-preview", "gemini-live-2.5-flash-preview")
    val FEMALE = listOf("Zephyr", "Kore", "Aoede", "Leda", "Callirrhoe", "Autonoe", "Despina", "Erinome", "Laomedeia", "Achernar", "Gacrux", "Pulcherrima", "Sulafat", "Vindemiatrix")
    val MALE = listOf("Puck", "Charon", "Fenrir", "Orus", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Algenib", "Rasalgethi", "Alnilam", "Schedar", "Achird", "Zubenelgenubi", "Sadachbia", "Sadaltager")

    lateinit var ctx: Context
    private lateinit var tools: Tools
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    var uiListener: ((String, JSONObject) -> Unit)? = null
    var state = "standby"; private set
    var stateText = ""; private set

    @Volatile var active = false; private set
    @Volatile private var ready = false
    private var ws: WebSocket? = null
    private var sessionId = 0
    private var modelIdx = 0
    private var reconnects = 0
    private var resumeHandle: String? = null
    private var thinkOff = true               // answer without "thinking" first = much faster; falls back if a model refuses
    private var resumeTime = 0L
    private var lastActivity = 0L
    private val pending = ArrayList<String>()          // audio/text queued while connecting
    private var userBuf = StringBuilder()
    private var novaBuf = StringBuilder()

    // ---- audio
    private var micOn = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val outQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastPlay = 0L
    @Volatile private var outLevel = 0f
    val speaking get() = outQ.isNotEmpty() || System.currentTimeMillis() - lastPlay < 700

    // ---- wake word
    private var vosk: Model? = null
    private var wake: Recognizer? = null
    private var quiet = 0
    private var lastHit = 0L
    var wakeStatus = "off"; private set

    fun init(c: Context) {
        ctx = c.applicationContext
        tools = Tools(ctx)
        thread(name = "player") { player() }
        thread(name = "watchdog") { watchdog() }
    }

    // ------------------------------------------------------------------ UI events
    fun ui(ev: String, data: JSONObject = JSONObject()) {
        val l = uiListener ?: return
        main.post { l(ev, data) }
    }

    private fun setState(s: String, t: String = "") {
        state = s; stateText = t
        FloatingOrb.state = s
        ui("state", JSONObject().put("state", s).put("text", t))
        NovaService.update()
    }

    fun standbyText() = if (Prefs.wakeWord && wake != null) "Say “Nova” or tap the orb" else "Tap the orb to talk"

    // ------------------------------------------------------------------ microphone
    fun hasMic() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (micOn || !hasMic()) return
        micOn = true
        if (Prefs.wakeWord) pool.execute { loadWake() }
        thread(name = "mic") {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            try {
                // VOICE_COMMUNICATION = the phone's "call" microphone processing: echo cancelling + noise
                // suppression tuned for the voice right at the phone, so far-away voices and noise are much weaker.
                val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (_: Exception) {}
                try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true } catch (_: Exception) {}
                rec = r
                r.startRecording()
                val buf = ShortArray(1600)                  // 100 ms
                while (micOn) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) onMic(buf, n)
                }
                r.stop(); r.release()
            } catch (e: Exception) {
                Log.e(TAG, "mic", e)
                ui("error", JSONObject().put("text", "Microphone problem: ${e.message}"))
            }
            rec = null
        }
        if (state == "standby") setState("standby", standbyText())
    }

    fun stopMic() { micOn = false }

    private var lvlT = 0L
    private var noise = 200.0                 // learned background level of the room
    private var gateOpen = 0                  // frames the voice gate stays open (hang-over keeps word endings)
    private var prev: ByteArray? = null       // previous 100 ms, sent when the gate opens so first syllables aren't cut
    private val zeros = ByteArray(3200)

    /** Only the voice close to the phone passes; distant voices / TV / fan noise become silence. */
    private fun gateThreshold(): Double = if (Prefs.micMode == "near") maxOf(700.0, noise * 3.5) else maxOf(250.0, noise * 2.0)

    private fun audioMsg(b: ByteArray) = JSONObject().put("realtimeInput", JSONObject().put("audio",
        JSONObject().put("data", Base64.encodeToString(b, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString()

    private fun onMic(buf: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
        val rms = sqrt(sum / n)
        val thr = gateThreshold()
        if (rms < thr && gateOpen == 0) noise = noise * 0.97 + minOf(rms, noise * 2.5) * 0.03
        val talking = speaking
        if (!active || talking) spot(buf, n, rms, thr)
        if (!active || talking) { gateOpen = 0; prev = null; return }
        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bytes.putShort(buf[i])
        val cur = bytes.array()
        if (rms >= thr) {
            if (gateOpen == 0) prev?.let { sendOrQueue(audioMsg(it)) }
            gateOpen = 6                                          // keep open 600 ms after the last loud frame
            lastActivity = System.currentTimeMillis()
        }
        if (gateOpen > 0) { gateOpen--; sendOrQueue(audioMsg(cur)) }
        else if (ready) ws?.send(audioMsg(if (cur.size == zeros.size) zeros else ByteArray(cur.size)))   // silence keeps the turn detector moving
        prev = cur
        val now = System.currentTimeMillis()
        if (now - lvlT > 90) {
            lvlT = now
            val lv = if (gateOpen > 0) (rms / 2500).coerceAtMost(1.0) else 0.0
            ui("level", JSONObject().put("mic", lv)); FloatingOrb.level = lv.toFloat()
        }
    }

    // ------------------------------------------------------------------ wake word ("Nova" / "Stop"), offline
    private fun loadWake() {
        if (vosk != null) { makeRecognizer(); return }
        val dir = File(ctx.filesDir, "vosk-model")
        try {
            if (File(dir, "graph/Gr.fst").exists()) dir.deleteRecursively()      // old full model (68 MB) -> replace with the slim one
            if (!File(dir, "am").exists()) {
                wakeStatus = "downloading"
                ui("info", JSONObject().put("text", "Downloading the offline “Nova” wake-word model (one time)…"))
                URL("https://github.com/procoder-manik/nova-android/releases/download/wake-model/nova-wake-model.zip").openStream().use { input ->
                    ZipInputStream(input).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val rel = e.name.substringAfter('/', "")
                            if (rel.isNotEmpty()) {
                                val f = File(dir, rel)
                                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                            }
                            e = z.nextEntry
                        }
                    }
                }
            }
            vosk = Model(dir.absolutePath)
            makeRecognizer()
            ui("info", JSONObject().put("text", "Ready — say “Nova” anytime."))
        } catch (e: Exception) {
            Log.e(TAG, "wake", e)
            wakeStatus = "error"
            dir.deleteRecursively()
            ui("error", JSONObject().put("text", "Wake word unavailable (${e.message}). Tap the orb to talk."))
        }
        if (state == "standby") setState("standby", standbyText())
    }

    private fun makeRecognizer() {
        val m = vosk ?: return
        wake = Recognizer(m, 16000f, "[\"nova\", \"hey nova\", \"ok nova\", \"nova talk with me\", \"stop\", \"nova stop\", \"[unk]\"]")
        wakeStatus = "ready"
    }

    fun setWake(on: Boolean) {
        Prefs.wakeWord = on
        if (on) pool.execute { loadWake() } else { wake = null; wakeStatus = "off" }
        if (state == "standby") setState("standby", standbyText())
    }

    private fun spot(buf: ShortArray, n: Int, rms: Double, thr: Double) {
        val r = wake ?: return
        if (!Prefs.wakeWord) return
        // in "near" mode only a close voice can wake Nova; quiet rooms cost almost no battery (nothing is decoded)
        quiet = if (rms > (if (Prefs.micMode == "near") thr * 0.8 else 220.0)) 0 else quiet + 1
        if (quiet > 12) { if (quiet == 13) r.reset(); return }
        val text = try {
            if (r.acceptWaveForm(buf, n)) JSONObject(r.result).optString("text") else JSONObject(r.partialResult).optString("partial")
        } catch (e: Exception) { "" }
        if (text.isEmpty() || System.currentTimeMillis() - lastHit < 1500) return
        if (text.contains("stop") && (speaking || active)) {
            lastHit = System.currentTimeMillis(); r.reset(); stopSpeaking()
        } else if (text.contains("nova") && !active) {
            lastHit = System.currentTimeMillis(); r.reset(); main.post { activate() }
        }
    }

    // ------------------------------------------------------------------ speaker
    private fun player() {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        t.play()
        while (true) {
            val b = outQ.take()
            var s = 0L
            var i = 0
            while (i + 1 < b.size) { val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort(); s += Math.abs(v.toInt()); i += 8 }
            outLevel = (s / (b.size / 8.0 + 1) / 3000.0).toFloat()
            t.write(b, 0, b.size)
            lastPlay = System.currentTimeMillis()
        }
    }

    fun stopSpeaking() {
        outQ.clear()
        track?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
        ui("state", JSONObject().put("state", "listening").put("text", "Stopped. Listening…"))
    }

    private fun chime() {
        val sr = 24000
        val notes = listOf(880.0 to 0.06, 1320.0 to 0.09)
        val out = ByteBuffer.allocate(notes.sumOf { (sr * it.second).toInt() } * 2).order(ByteOrder.LITTLE_ENDIAN)
        for ((f, d) in notes) {
            val n = (sr * d).toInt()
            for (k in 0 until n) {
                val tt = k.toDouble() / sr
                val env = minOf(1.0, tt * 150) * minOf(1.0, (d - tt) * 60)
                out.putShort((Math.sin(2 * Math.PI * f * tt) * env * 0.2 * 32767).toInt().toShort())
            }
        }
        outQ.put(out.array())
        lastPlay = 0
    }

    // ------------------------------------------------------------------ session control
    fun activate(text: String? = null) {
        if (Prefs.apiKey.isBlank()) { ui("need_key"); return }
        if (!hasMic()) { ui("need_mic"); return }
        startMic()
        if (active) { if (text != null) sendText(text); return }
        active = true
        reconnects = 0
        thinkOff = true
        lastActivity = System.currentTimeMillis()
        FloatingOrb.show(ctx)
        chime()
        ui("chime")
        if (text != null) pending.add(textMsg(text).also { ui("transcript", JSONObject().put("role", "user").put("text", text).put("final", true)) })
        connect()
    }

    fun toggle() {
        if (!active) activate()
        else if (speaking) stopSpeaking()
        else deactivate()
    }

    fun deactivate() {
        FloatingOrb.hide()
        active = false
        ready = false
        sessionId++
        ws?.close(1000, "bye"); ws = null
        synchronized(pending) { pending.clear() }
        setState("standby", standbyText())
    }

    fun sendText(text: String) {
        ui("transcript", JSONObject().put("role", "user").put("text", text).put("final", true))
        Prefs.addHistory("user", text)
        if (!active) { activate(); synchronized(pending) { pending.add(textMsg(text)) }; return }
        sendOrQueue(textMsg(text))
        lastActivity = System.currentTimeMillis()
    }

    private fun textMsg(t: String) = JSONObject().put("clientContent", JSONObject()
        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t)))))
        .put("turnComplete", true)).toString()

    private fun sendOrQueue(msg: String) {
        val w = ws
        if (ready && w != null) w.send(msg)
        else synchronized(pending) { if (pending.size < 60) pending.add(msg) }
    }

    private fun systemPrompt(): String {
        val name = Prefs.userName.ifBlank { "the user" }
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        return """You are NOVA, a warm, witty, emotionally aware female personal assistant living on $name's Android phone in Bangladesh.
Built by Manik Hossain (manikhossain.in). Free and open source.

PERSONALITY: natural, friendly, caring, a little playful when it fits; never robotic.
LISTENING: The user speaks Bangla (Dhaka accent), English or a mix. If a name or number is unclear, confirm briefly instead of guessing.
LANGUAGE: Reply in the user's language (natural Bangladeshi Bangla, or English). Keep spoken answers short (1-3 sentences) and offer more.
ACTIONS: Use your tools to act on the phone right away (open apps, websites, YouTube, calls, WhatsApp/SMS drafts, alarms, timers, flashlight, music, maps). Never claim something worked unless the tool says so.
INSIDE APPS (you can see and use the phone like a person): open_app → read_screen to see what's inside → tap / type_text / scroll → read_screen again to check. Use this for anything an app shows (Calendar app, Gmail app, Samsung Notes / Keep, Messenger, settings...). For notes: open the notes app, tap the new-note button, type_text the note; or use notion_create_page / notion_append for Notion. When asked to summarise, write a clean summary yourself first. Before pressing Send / Post / Delete / Pay in any app, read back what will happen and wait for a clear yes. Never type passwords, OTPs or payment details.
CALENDAR: use calendar_events to answer "do I have a meeting?" (it reads the phone's calendars directly) and add_calendar_event to schedule.
CALLS & MESSAGES: If several contacts match, ask which one. WhatsApp/SMS tools only type the message - tell the user to press send.
NOTIFICATIONS & EMAIL: Use read_notifications for new WhatsApp/Messenger/SMS messages and missed calls; use check_email for Gmail. Summarise briefly; don't read private details aloud unless asked.
KNOWLEDGE: For news and anything current use Google Search and mention the source (Prothom Alo, The Daily Star, bdnews24, BBC...).
MEMORY: When the user corrects you or shares a preference, call remember right away. What you know:
${Prefs.memoryPrompt()}
HONESTY: If a tool fails or needs a permission, say so plainly and how to fix it. Never enter passwords or payment details.
CONTINUITY: The connection may refresh; the recent conversation below is real - continue from it.
RECENT CONVERSATION:
${Prefs.recentConversation().ifBlank { "(none)" }}
Now: $now."""
    }

    private fun setupMsg(model: String): String {
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .apply { if (thinkOff) put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) }
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())).put(JSONObject().put("functionDeclarations", Tools.declarations())))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH").put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 200).put("silenceDurationMs", 600)))
        val sr = JSONObject()
        if (resumeHandle != null && System.currentTimeMillis() - resumeTime < 100 * 60_000) sr.put("handle", resumeHandle)
        setup.put("sessionResumption", sr)
        return JSONObject().put("setup", setup).toString()
    }

    private fun connect() {
        val id = ++sessionId
        ready = false
        setState("connecting", if (reconnects > 0) "Reconnecting…" else "Connecting…")
        val saved = Prefs.model
        if (saved.isNotBlank() && MODELS.contains(saved) && reconnects == 0) modelIdx = MODELS.indexOf(saved)
        val model = MODELS[modelIdx % MODELS.size]
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=" + Prefs.apiKey
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (id != sessionId) return
                webSocket.send(setupMsg(model))
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (id == sessionId) handle(text, model) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (id == sessionId) handle(bytes.utf8(), model) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed(id, code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed(id, -1, t.message ?: "network error") }
        })
    }

    private fun closed(id: Int, code: Int, reason: String) {
        if (id != sessionId || !active) return
        Log.w(TAG, "closed $code $reason")
        val low = reason.lowercase(Locale.ROOT)
        if (!ready) {
            if (low.contains("api key") || low.contains("api_key")) {
                ui("error", JSONObject().put("text", "Your Gemini API key isn't valid. Open Settings and paste a new key."))
                active = false; setState("standby", standbyText()); return
            }
            resumeHandle = null
            if (thinkOff) thinkOff = false else { modelIdx++; thinkOff = true }
            if (modelIdx >= MODELS.size * 2 || reconnects > 6) {
                ui("error", JSONObject().put("text", "Couldn't connect to Gemini: $reason"))
                active = false; modelIdx = 0; setState("standby", standbyText()); return
            }
        }
        reconnects++
        if (reconnects > 8) { deactivate(); return }
        main.postDelayed({ if (active) connect() }, 400)
    }

    private fun handle(raw: String, model: String) {
        val m = try { JSONObject(raw) } catch (e: Exception) { return }
        if (m.has("setupComplete")) {
            ready = true
            Prefs.model = model
            lastActivity = System.currentTimeMillis()
            setState("listening", "Listening…")
            val w = ws
            synchronized(pending) { if (w != null) pending.forEach { w.send(it) }; pending.clear() }
            return
        }
        m.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    outQ.put(Base64.decode(d.optString("data"), Base64.DEFAULT))
                    if (state != "speaking") setState("speaking")
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                userBuf.append(it); lastActivity = System.currentTimeMillis()
                ui("transcript", JSONObject().put("role", "user").put("text", userBuf.toString()).put("final", false))
                if (state != "thinking") setState("thinking")
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                novaBuf.append(it)
                ui("transcript", JSONObject().put("role", "nova").put("text", novaBuf.toString()).put("final", false))
            }
            sc.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { ch ->
                val items = JSONArray()
                for (i in 0 until ch.length()) ch.getJSONObject(i).optJSONObject("web")?.let { w -> items.put(JSONObject().put("title", w.optString("title")).put("uri", w.optString("uri"))) }
                if (items.length() > 0) ui("sources", JSONObject().put("items", items))
            }
            if (sc.optBoolean("interrupted")) outQ.clear()
            if (sc.optBoolean("turnComplete")) {
                if (userBuf.isNotEmpty()) { Prefs.addHistory("user", userBuf.toString()); ui("transcript", JSONObject().put("role", "user").put("text", userBuf.toString()).put("final", true)) }
                if (novaBuf.isNotEmpty()) { Prefs.addHistory("nova", novaBuf.toString()); ui("transcript", JSONObject().put("role", "nova").put("text", novaBuf.toString()).put("final", true)) }
                userBuf = StringBuilder(); novaBuf = StringBuilder()
                lastActivity = System.currentTimeMillis()
                setState("listening", "Listening…")
            }
        }
        m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls -> pool.execute { runTools(calls) } }
        m.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable") && u.optString("newHandle").isNotEmpty()) { resumeHandle = u.optString("newHandle"); resumeTime = System.currentTimeMillis() }
        }
        if (m.has("goAway")) { ws?.close(1000, "go_away"); closed(sessionId, 1000, "go_away") }
    }

    private fun runTools(calls: JSONArray) {
        val responses = JSONArray()
        var end = false
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val name = c.optString("name")
            val args = c.optJSONObject("args") ?: JSONObject()
            ui("tool", JSONObject().put("name", name).put("args", args))
            setState("working", name.replace('_', ' '))
            val result = when (name) {
                "end_conversation" -> { end = true; JSONObject().put("result", "ok") }
                "look_at_screen" -> lookAtScreen()
                else -> tools.run(name, args)
            }
            Prefs.addHistory("tool", "$name(${args.toString().take(120)}) -> ${result.optString("status", "ok")}")
            responses.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", result))
            lastActivity = System.currentTimeMillis()
        }
        ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
        if (end) thread {
            Thread.sleep(1500)
            var k = 0
            while (speaking && k++ < 60) Thread.sleep(250)
            main.post { deactivate() }
        }
    }

    /** Sends a screenshot to Gemini so Nova can actually see the screen. */
    private fun lookAtScreen(): JSONObject {
        val jpg = NovaAccess.screenshotJpeg()
            ?: return NovaAccess.readScreen().put("note", "Screenshot not available; here is the screen text instead.")
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("video",
            JSONObject().put("data", Base64.encodeToString(jpg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))).toString())
        return JSONObject().put("status", "screenshot_sent").put("note", "The screenshot was just sent to you as an image; describe what you see.")
    }

    private fun watchdog() {
        while (true) {
            Thread.sleep(1000)
            if (active) {
                if (speaking) { lastActivity = System.currentTimeMillis(); ui("level", JSONObject().put("out", outLevel.toDouble())); FloatingOrb.level = outLevel }
                else if (System.currentTimeMillis() - lastActivity > Prefs.activeSeconds * 1000L && state != "working" && state != "connecting") main.post { deactivate() }
            }
        }
    }
}
