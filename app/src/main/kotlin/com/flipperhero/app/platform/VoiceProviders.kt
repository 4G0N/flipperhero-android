package com.flipperhero.app.platform

import android.content.Context
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Text-to-speech, written to DETECT the absence of a provider and let the caller degrade to text.
 * GrapheneOS often ships no TTS engine; we never assume one exists. Voice is optional for v1.
 */
interface TtsProvider {
    suspend fun isAvailable(): Boolean
    fun speak(text: String)
    fun stop()
    fun shutdown()
}

class AndroidTtsProvider(context: Context) : TtsProvider {
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            val ok = status == TextToSpeech.SUCCESS &&
                engine != null &&
                engine.engines.isNotEmpty() &&
                engine.setLanguage(Locale.getDefault()) != TextToSpeech.LANG_MISSING_DATA
            if (!ready.isCompleted) ready.complete(ok)
        }
    }

    override suspend fun isAvailable(): Boolean = withTimeoutOrNull(3_000) { ready.await() } == true

    override fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "flipperhero")
    }

    override fun stop() { tts?.stop() }
    override fun shutdown() { tts?.shutdown(); tts = null }
}

/**
 * Speech-to-text availability. Recognition streaming UI is deferred for v1; this reports whether a
 * recognizer exists so the UI can show a mic affordance or fall back to typing. On a de-Googled
 * device with no recognition service this returns false and the UI stays text-only.
 */
interface SpeechProvider {
    fun isAvailable(): Boolean
}

class AndroidSpeechProvider(private val context: Context) : SpeechProvider {
    override fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)
}
