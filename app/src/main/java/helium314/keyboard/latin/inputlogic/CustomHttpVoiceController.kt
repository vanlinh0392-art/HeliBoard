package helium314.keyboard.latin.inputlogic

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max

internal class CustomHttpVoiceController(
    private val latinIME: LatinIME,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onSpeechStarted()
        fun onSpeechActivity(activityAtElapsed: Long)
        fun onTranscriptionResult(text: String)
        fun onNoMatch()
        fun onError(statusResId: Int, throwable: Throwable? = null)
    }

    private val httpClient = OkHttpClient()
    private val audioPcmLock = Any()
    private var audioPcmBuffer = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    private var activeHttpCall: Call? = null
    private var lastActivityDispatchAtElapsed = 0L
    private var speechDetected = false
    private var canceled = false

    fun start(): Boolean {
        cancel()
        canceled = false
        speechDetected = false
        lastActivityDispatchAtElapsed = 0L
        audioPcmBuffer = ByteArrayOutputStream()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "AudioRecord reported invalid buffer size=$minBufferSize")
            return false
        }
        val bufferSize = max(minBufferSize, READ_CHUNK_BYTES)
        val recorder = createAudioRecord(bufferSize) ?: return false
        audioRecord = recorder

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            releaseRecorder(recorder)
            audioRecord = null
            return false
        }

        audioRecordThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (!canceled && audioRecord === recorder) {
                val bytesRead = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord read failed", e)
                    break
                }
                if (bytesRead <= 0) {
                    continue
                }
                synchronized(audioPcmLock) {
                    audioPcmBuffer.write(buffer, 0, bytesRead)
                }
                val peakAmplitude = computePeakAmplitude(buffer, bytesRead)
                if (peakAmplitude >= ACTIVITY_THRESHOLD_PCM) {
                    val activityAt = SystemClock.elapsedRealtime()
                    if (activityAt - lastActivityDispatchAtElapsed >= ACTIVITY_DISPATCH_MS) {
                        lastActivityDispatchAtElapsed = activityAt
                        if (!speechDetected) {
                            speechDetected = true
                            handler.post { if (!canceled) listener.onSpeechStarted() }
                        }
                        handler.post { if (!canceled) listener.onSpeechActivity(activityAt) }
                    }
                }
            }
        }, "VoiceCustomHttpRecorder").apply {
            isDaemon = true
            start()
        }

        return true
    }

    fun stopAndTranscribe(endpoint: String) {
        val pcmData = stopRecorderAndCollectPcm()
        if (canceled) {
            return
        }
        if (!speechDetected || pcmData.isEmpty()) {
            handler.post { if (!canceled) listener.onNoMatch() }
            return
        }

        val wavPayload = buildWavPayload(pcmData)
        Thread({
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(wavPayload.toRequestBody(WAV_MEDIA_TYPE))
                    .build()
                val call = httpClient.newCall(request)
                activeHttpCall = call
                call.execute().use { response ->
                    activeHttpCall = null
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val responseBody = response.body?.string().orEmpty()
                    val text = parseResponseText(responseBody)
                    handler.post {
                        if (canceled) return@post
                        if (text.isBlank()) listener.onNoMatch()
                        else listener.onTranscriptionResult(text)
                    }
                }
            } catch (e: Exception) {
                if (e is IOException && e.message?.contains("Canceled", ignoreCase = true) == true && canceled) {
                    return@Thread
                }
                Log.e(TAG, "Custom HTTP transcription failed", e)
                handler.post { if (!canceled) listener.onError(R.string.voice_status_error, e) }
            } finally {
                activeHttpCall = null
            }
        }, "VoiceCustomHttpUpload").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        canceled = true
        try {
            activeHttpCall?.cancel()
        } catch (_: Exception) {
        }
        activeHttpCall = null
        stopRecorderAndCollectPcm()
    }

    fun destroy() {
        cancel()
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        if (ContextCompat.checkSelfPermission(latinIME, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted while creating AudioRecord")
            return null
        }
        val audioSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (audioSource in audioSources) {
            try {
                val recorder = AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Initialized custom HTTP recorder with audioSource=$audioSource")
                    return recorder
                }
                releaseRecorder(recorder)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord with source=$audioSource", e)
            }
        }
        return null
    }

    private fun stopRecorderAndCollectPcm(): ByteArray {
        val recorder = audioRecord
        audioRecord = null
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }
        releaseRecorder(recorder)

        val thread = audioRecordThread
        audioRecordThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(THREAD_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        synchronized(audioPcmLock) {
            val data = audioPcmBuffer.toByteArray()
            audioPcmBuffer.reset()
            return data
        }
    }

    private fun releaseRecorder(recorder: AudioRecord?) {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
    }

    private fun buildWavPayload(pcmData: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(WAV_HEADER_SIZE - 8 + pcmData.size)
        header.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        header.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(CHANNEL_COUNT.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE)
        header.putShort((CHANNEL_COUNT * BYTES_PER_SAMPLE).toShort())
        header.putShort((BYTES_PER_SAMPLE * 8).toShort())
        header.put("data".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(pcmData.size)
        return header.array() + pcmData
    }

    private fun computePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var peak = 0
        var index = 0
        while (index + 1 < bytesRead) {
            val lo = buffer[index].toInt() and 0xFF
            val hi = buffer[index + 1].toInt()
            val sample = (hi shl 8) or lo
            peak = max(peak, abs(sample.toShort().toInt()))
            index += 2
        }
        return peak
    }

    private fun parseResponseText(responseBody: String): String {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        return try {
            when {
                trimmed.startsWith("{") -> extractText(JSONObject(trimmed))
                trimmed.startsWith("[") -> extractText(JSONArray(trimmed))
                else -> trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun extractText(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is JSONObject -> {
                for (key in RESPONSE_TEXT_KEYS) {
                    val text = value.optString(key).trim()
                    if (text.isNotEmpty() && text != "null") {
                        return text
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val text = extractText(value.opt(keys.next()))
                    if (text.isNotEmpty()) {
                        return text
                    }
                }
                ""
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val text = extractText(value.opt(i))
                    if (text.isNotEmpty()) {
                        return text
                    }
                }
                ""
            }
            else -> ""
        }
    }

    companion object {
        private const val TAG = "CustomHttpVoice"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val READ_CHUNK_BYTES = 4096
        private const val ACTIVITY_THRESHOLD_PCM = 900
        private const val ACTIVITY_DISPATCH_MS = 120L
        private const val THREAD_JOIN_TIMEOUT_MS = 750L
        private const val WAV_HEADER_SIZE = 44

        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        private val RESPONSE_TEXT_KEYS = arrayOf("text", "transcript", "result")
    }
}
