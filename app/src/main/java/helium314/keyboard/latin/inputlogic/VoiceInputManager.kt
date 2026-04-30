package helium314.keyboard.latin.inputlogic

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsActivity
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.VisibleVoiceRecognitionCoordinator
import helium314.keyboard.latin.voice.VoiceRecognitionActivity
import java.util.ArrayList

class VoiceInputManager(private val latinIME: LatinIME) {

    private enum class VoiceState {
        IDLE,
        LISTENING,
        FINALIZING,
    }

    private enum class VoiceBackendMode {
        GOOGLE_VISIBLE,
        CUSTOM_HTTP,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val visibleVoiceListener = object : VisibleVoiceRecognitionCoordinator.Listener {
        override fun onResult(sessionId: Long, text: String, targetPackageName: String?) {
            if (sessionId != activeVisibleSessionId || activeBackendMode != VoiceBackendMode.GOOGLE_VISIBLE) {
                return
            }
            finishWithCommittedText(text, targetPackageName)
        }

        override fun onNoMatch(sessionId: Long) {
            if (sessionId != activeVisibleSessionId) {
                return
            }
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_no_match)
        }

        override fun onCancelled(sessionId: Long) {
            if (sessionId != activeVisibleSessionId) {
                return
            }
            resetSession(clearEditorComposition = true, clearStatus = true)
        }

        override fun onError(sessionId: Long, statusResId: Int) {
            if (sessionId != activeVisibleSessionId) {
                return
            }
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(statusResId)
        }
    }
    private val customHttpVoiceController = CustomHttpVoiceController(latinIME, handler, object : CustomHttpVoiceController.Listener {
        override fun onSpeechStarted() {
            if (voiceState != VoiceState.LISTENING || activeBackendMode != VoiceBackendMode.CUSTOM_HTTP) {
                return
            }
            Log.d(TAG, "Custom HTTP backend detected speech start")
            speechStarted = true
            val now = SystemClock.elapsedRealtime()
            lastSpeechSignalAtElapsed = now
            scheduleSpeechWatchdog(AUTO_STOP_SILENCE_MS)
        }

        override fun onSpeechActivity(activityAtElapsed: Long) {
            if (voiceState != VoiceState.LISTENING || activeBackendMode != VoiceBackendMode.CUSTOM_HTTP) {
                return
            }
            lastSpeechSignalAtElapsed = activityAtElapsed
            scheduleSpeechWatchdog(AUTO_STOP_SILENCE_MS)
        }

        override fun onTranscriptionResult(text: String) {
            if (voiceState != VoiceState.FINALIZING || activeBackendMode != VoiceBackendMode.CUSTOM_HTTP) {
                return
            }
            finishWithCommittedText(text, targetPackageName)
        }

        override fun onNoMatch() {
            if (voiceState == VoiceState.IDLE) {
                return
            }
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_no_match)
        }

        override fun onError(statusResId: Int, throwable: Throwable?) {
            Log.e(TAG, "Custom HTTP backend failed", throwable)
            if (voiceState == VoiceState.IDLE) {
                return
            }
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(statusResId)
        }
    })

    private var voiceState = VoiceState.IDLE
    private var targetPackageName: String? = null
    private var allowedLanguageTags = arrayListOf<String>()
    private var speechStarted = false
    private var lastSpeechSignalAtElapsed = 0L
    private var activeBackendMode = VoiceBackendMode.GOOGLE_VISIBLE
    private var activeVisibleSessionId = NO_VISIBLE_SESSION_ID

    private val clearStatusRunnable = Runnable {
        latinIME.clearVoiceInputStatus(true)
    }

    private val speechWatchdogRunnable = Runnable {
        if (voiceState != VoiceState.LISTENING || activeBackendMode != VoiceBackendMode.CUSTOM_HTTP) {
            return@Runnable
        }
        if (!speechStarted) {
            Log.w(TAG, "Auto stopping voice session after ${PRE_SPEECH_TIMEOUT_MS}ms without speech")
            stopListeningForFinalization("pre-speech watchdog")
            return@Runnable
        }

        val silentForMs = SystemClock.elapsedRealtime() - lastSpeechSignalAtElapsed
        if (silentForMs >= AUTO_STOP_SILENCE_MS) {
            Log.d(TAG, "Auto stopping voice session after ${silentForMs}ms of silence")
            stopListeningForFinalization("silence watchdog")
            return@Runnable
        }

        scheduleSpeechWatchdog(AUTO_STOP_SILENCE_MS - silentForMs)
    }

    fun startListening() {
        latinIME.currentInputConnection?.finishComposingText()
        targetPackageName = latinIME.currentVoiceInputTargetPackageName
        allowedLanguageTags = ArrayList(latinIME.currentVoiceInputAllowedLanguageTags)
        Log.d(TAG, "startListening targetPackage=$targetPackageName languageTags=$allowedLanguageTags state=$voiceState")

        when (voiceState) {
            VoiceState.LISTENING -> {
                if (activeBackendMode == VoiceBackendMode.CUSTOM_HTTP) {
                    if (speechStarted) {
                        stopListeningForFinalization("duplicate tap after speech start")
                    } else {
                        Log.d(TAG, "Cancelling custom HTTP voice session before speech start")
                        cancel()
                    }
                } else {
                    Log.d(TAG, "Ignoring duplicate tap while visible Google voice is active")
                }
                return
            }
            VoiceState.FINALIZING -> {
                Log.d(TAG, "Voice session already finalizing, ignoring duplicate tap")
                return
            }
            VoiceState.IDLE -> Unit
        }

        handler.removeCallbacks(clearStatusRunnable)
        when (resolveVoiceBackendMode()) {
            VoiceBackendMode.CUSTOM_HTTP -> {
                if (!PermissionsUtil.checkAllPermissionsGranted(latinIME, Manifest.permission.RECORD_AUDIO)) {
                    Log.w(TAG, "RECORD_AUDIO not granted for custom HTTP backend")
                    resetSession(clearEditorComposition = false, clearStatus = false)
                    PermissionsActivity.run(latinIME, Manifest.permission.RECORD_AUDIO)
                    showTransientStatus(R.string.voice_status_permission_required)
                    return
                }
                startCustomHttpListening()
            }
            VoiceBackendMode.GOOGLE_VISIBLE -> {
                startGoogleVisibleListening()
            }
        }
    }

    fun stopListening() {
        stopListeningForFinalization("external stop")
    }

    fun cancel() {
        if (voiceState == VoiceState.IDLE) {
            return
        }
        if (activeBackendMode == VoiceBackendMode.CUSTOM_HTTP) {
            customHttpVoiceController.cancel()
        }
        resetSession(clearEditorComposition = true, clearStatus = true)
    }

    fun destroy() {
        resetSession(clearEditorComposition = true, clearStatus = true)
    }

    private fun resolveVoiceBackendMode(): VoiceBackendMode {
        return when (latinIME.prefs().getString(Settings.PREF_VOICE_BACKEND, Defaults.PREF_VOICE_BACKEND)) {
            Defaults.PREF_VOICE_BACKEND_CUSTOM_HTTP -> VoiceBackendMode.CUSTOM_HTTP
            else -> VoiceBackendMode.GOOGLE_VISIBLE
        }
    }

    private fun getCustomHttpEndpoint(): String {
        return latinIME.prefs().getString(
            Settings.PREF_VOICE_CUSTOM_HTTP_ENDPOINT,
            Defaults.PREF_VOICE_CUSTOM_HTTP_ENDPOINT,
        )?.trim().orEmpty()
    }

    private fun startCustomHttpListening() {
        val endpoint = getCustomHttpEndpoint()
        if (endpoint.isEmpty()) {
            Log.w(TAG, "Custom HTTP backend selected without endpoint")
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_backend_not_configured)
            return
        }

        val sessionTargetPackageName = targetPackageName
        val sessionAllowedLanguageTags = ArrayList(allowedLanguageTags)
        resetSession(clearEditorComposition = false, clearStatus = false)
        activeBackendMode = VoiceBackendMode.CUSTOM_HTTP
        targetPackageName = sessionTargetPackageName
        allowedLanguageTags = sessionAllowedLanguageTags
        speechStarted = false
        lastSpeechSignalAtElapsed = SystemClock.elapsedRealtime()

        if (!customHttpVoiceController.start()) {
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_recording_failed)
            return
        }

        transitionTo(VoiceState.LISTENING)
        showPersistentStatus(R.string.voice_status_listening)
        scheduleSpeechWatchdog(PRE_SPEECH_TIMEOUT_MS)
    }

    private fun startGoogleVisibleListening() {
        val sessionTargetPackageName = targetPackageName
        val sessionAllowedLanguageTags = ArrayList(allowedLanguageTags)
        resetSession(clearEditorComposition = false, clearStatus = false)
        activeBackendMode = VoiceBackendMode.GOOGLE_VISIBLE
        val session = VisibleVoiceRecognitionCoordinator.openSession(
            targetPackageName = sessionTargetPackageName,
            allowedLanguageTags = sessionAllowedLanguageTags,
            listener = visibleVoiceListener,
        )
        activeVisibleSessionId = session.id
        targetPackageName = sessionTargetPackageName
        allowedLanguageTags = sessionAllowedLanguageTags
        transitionTo(VoiceState.LISTENING)
        try {
            VoiceRecognitionActivity.launch(latinIME, session.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch visible Google voice flow", e)
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_google_unavailable)
        }
    }

    private fun stopListeningForFinalization(reason: String) {
        if (voiceState != VoiceState.LISTENING || activeBackendMode != VoiceBackendMode.CUSTOM_HTTP) {
            return
        }
        Log.d(TAG, "stopListeningForFinalization reason=$reason")
        transitionTo(VoiceState.FINALIZING)
        showPersistentStatus(R.string.voice_status_processing)
        handler.removeCallbacks(speechWatchdogRunnable)
        customHttpVoiceController.stopAndTranscribe(getCustomHttpEndpoint())
    }

    private fun resetSession(
        clearEditorComposition: Boolean,
        clearStatus: Boolean,
        resumeSuggestions: Boolean = true,
    ) {
        handler.removeCallbacks(speechWatchdogRunnable)
        if (clearEditorComposition) {
            latinIME.currentInputConnection?.finishComposingText()
        }
        customHttpVoiceController.cancel()
        if (activeVisibleSessionId != NO_VISIBLE_SESSION_ID) {
            VisibleVoiceRecognitionCoordinator.clearSession(activeVisibleSessionId, visibleVoiceListener)
            activeVisibleSessionId = NO_VISIBLE_SESSION_ID
        }
        speechStarted = false
        lastSpeechSignalAtElapsed = 0L
        targetPackageName = null
        allowedLanguageTags.clear()
        activeBackendMode = VoiceBackendMode.GOOGLE_VISIBLE
        transitionTo(VoiceState.IDLE)
        if (clearStatus) {
            handler.removeCallbacks(clearStatusRunnable)
            latinIME.clearVoiceInputStatus(resumeSuggestions)
        }
    }

    private fun transitionTo(newState: VoiceState) {
        if (voiceState == newState) {
            return
        }
        Log.d(TAG, "Voice state $voiceState -> $newState")
        voiceState = newState
    }

    private fun finishWithCommittedText(text: String, targetPackageName: String?) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            resetSession(clearEditorComposition = true, clearStatus = false)
            showTransientStatus(R.string.voice_status_no_match)
            return
        }
        Log.d(TAG, "Committing recognized text length=${normalized.length}")
        latinIME.commitVoiceInputText(normalized, targetPackageName)
        resetSession(clearEditorComposition = false, clearStatus = false)
        latinIME.clearVoiceInputStatus(true)
    }

    private fun showPersistentStatus(messageResId: Int) {
        handler.removeCallbacks(clearStatusRunnable)
        latinIME.showVoiceInputStatus(latinIME.getString(messageResId))
    }

    private fun showTransientStatus(messageResId: Int, durationMs: Long = STATUS_MESSAGE_DURATION_MS) {
        handler.removeCallbacks(clearStatusRunnable)
        latinIME.showVoiceInputStatus(latinIME.getString(messageResId))
        handler.postDelayed(clearStatusRunnable, durationMs)
    }

    private fun scheduleSpeechWatchdog(delayMs: Long) {
        handler.removeCallbacks(speechWatchdogRunnable)
        handler.postDelayed(speechWatchdogRunnable, delayMs.coerceAtLeast(100L))
    }

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val NO_VISIBLE_SESSION_ID = -1L
        private const val PRE_SPEECH_TIMEOUT_MS = 5000L
        private const val AUTO_STOP_SILENCE_MS = 2000L
        private const val STATUS_MESSAGE_DURATION_MS = 1600L
    }
}
