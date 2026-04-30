package helium314.keyboard.latin.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.util.ArrayList

class VoiceRecognitionActivity : Activity() {

    private var sessionId = NO_SESSION_ID
    private var recognizerLaunched = false
    private var completionDispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION_ID)
        recognizerLaunched = savedInstanceState?.getBoolean(STATE_RECOGNIZER_LAUNCHED) ?: false
        completionDispatched = savedInstanceState?.getBoolean(STATE_COMPLETION_DISPATCHED) ?: false
        if (sessionId == NO_SESSION_ID || VisibleVoiceRecognitionCoordinator.getSession(sessionId) == null) {
            finishWithoutAnimation()
            return
        }
        if (!recognizerLaunched) {
            launchRecognition()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RECOGNIZER_LAUNCHED, recognizerLaunched)
        outState.putBoolean(STATE_COMPLETION_DISPATCHED, completionDispatched)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RECOGNIZE_SPEECH) {
            return
        }
        val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?: data?.getStringArrayListExtra(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestMatch = matches?.firstOrNull()?.trim().orEmpty()
        if (bestMatch.isNotEmpty()) {
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverResult(sessionId, bestMatch)
        } else if (resultCode == RESULT_CANCELED) {
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverCancelled(sessionId)
        } else {
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverNoMatch(sessionId)
        }
        finishWithoutAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && recognizerLaunched && !completionDispatched && sessionId != NO_SESSION_ID) {
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverCancelled(sessionId)
        }
    }

    private fun launchRecognition() {
        val session = VisibleVoiceRecognitionCoordinator.getSession(sessionId)
        if (session == null) {
            finishWithoutAnimation()
            return
        }
        val recognizerIntent = resolveRecognitionIntent(createRecognizerIntent(session.allowedLanguageTags))
        if (recognizerIntent == null) {
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverError(sessionId, R.string.voice_status_google_unavailable)
            finishWithoutAnimation()
            return
        }
        recognizerLaunched = true
        try {
            startActivityForResult(recognizerIntent, REQUEST_RECOGNIZE_SPEECH)
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Google speech UI", e)
            completionDispatched = true
            VisibleVoiceRecognitionCoordinator.deliverError(sessionId, R.string.voice_status_google_unavailable)
            finishWithoutAnimation()
        }
    }

    private fun createRecognizerIntent(allowedLanguageTags: ArrayList<String>): Intent {
        val primaryLanguageTag = allowedLanguageTags.firstOrNull().orEmpty()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (primaryLanguageTag.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLanguageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLanguageTag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    ArrayList(allowedLanguageTags),
                )
            }
        }
    }

    private fun resolveRecognitionIntent(baseIntent: Intent): Intent? {
        val components = queryRecognitionActivities(baseIntent)
        val googleComponent = components
            .sortedWith(compareBy<ComponentName>({ packageRank(it.packageName) }, { it.flattenToShortString() }))
            .firstOrNull { packageRank(it.packageName) < NON_GOOGLE_PACKAGE_RANK }
        if (googleComponent != null) {
            return Intent(baseIntent).setComponent(googleComponent)
        }
        val fallbackComponent = baseIntent.resolveActivity(packageManager)
        if (fallbackComponent != null && packageRank(fallbackComponent.packageName) < NON_GOOGLE_PACKAGE_RANK) {
            return Intent(baseIntent).setComponent(fallbackComponent)
        }
        return null
    }

    private fun queryRecognitionActivities(intent: Intent): List<ComponentName> {
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfos.mapNotNull { resolveInfo ->
            resolveInfo.activityInfo?.let { activityInfo ->
                ComponentName(activityInfo.packageName, activityInfo.name)
            }
        }
    }

    private fun packageRank(packageName: String): Int {
        return when {
            packageName == GOOGLE_APP_PACKAGE -> 0
            packageName.startsWith("com.google.android") -> 10
            else -> NON_GOOGLE_PACKAGE_RANK
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "VoiceRecognitionUi"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val STATE_RECOGNIZER_LAUNCHED = "recognizer_launched"
        private const val STATE_COMPLETION_DISPATCHED = "completion_dispatched"
        private const val REQUEST_RECOGNIZE_SPEECH = 1001
        private const val NO_SESSION_ID = -1L
        private const val NON_GOOGLE_PACKAGE_RANK = 100
        private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

        fun launch(context: Context, sessionId: Long) {
            val intent = Intent(context, VoiceRecognitionActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startActivity(intent)
        }
    }
}
