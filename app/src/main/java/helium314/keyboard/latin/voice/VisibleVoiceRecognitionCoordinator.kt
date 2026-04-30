package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong

internal object VisibleVoiceRecognitionCoordinator {
    interface Listener {
        fun onResult(sessionId: Long, text: String, targetPackageName: String?)
        fun onNoMatch(sessionId: Long)
        fun onCancelled(sessionId: Long)
        fun onError(sessionId: Long, statusResId: Int)
    }

    data class Session(
        val id: Long,
        val targetPackageName: String?,
        val allowedLanguageTags: ArrayList<String>,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextSessionId = AtomicLong(1L)
    private var activeSession: Session? = null
    private var activeListener: Listener? = null

    @Synchronized
    fun openSession(
        targetPackageName: String?,
        allowedLanguageTags: List<String>,
        listener: Listener,
    ): Session {
        val normalizedLanguageTags = LinkedHashSet<String>()
        allowedLanguageTags.filterTo(normalizedLanguageTags) { it.isNotBlank() }
        if (normalizedLanguageTags.isEmpty()) {
            normalizedLanguageTags.add("vi-VN")
            normalizedLanguageTags.add("en-US")
        }
        val session = Session(
            id = nextSessionId.getAndIncrement(),
            targetPackageName = targetPackageName,
            allowedLanguageTags = ArrayList(normalizedLanguageTags),
        )
        activeSession = session
        activeListener = listener
        return session
    }

    @Synchronized
    fun getSession(sessionId: Long): Session? {
        return activeSession?.takeIf { it.id == sessionId }
    }

    @Synchronized
    fun clearSession(sessionId: Long, listener: Listener? = null) {
        val session = activeSession ?: return
        if (session.id != sessionId) {
            return
        }
        if (listener != null && activeListener !== listener) {
            return
        }
        activeSession = null
        activeListener = null
    }

    fun deliverResult(sessionId: Long, text: String) {
        val (listener, targetPackageName) = synchronized(this) {
            val session = activeSession ?: return
            if (session.id != sessionId) {
                return
            }
            val callback = activeListener
            activeSession = null
            activeListener = null
            callback to session.targetPackageName
        }
        mainHandler.post {
            listener?.onResult(sessionId, text, targetPackageName)
        }
    }

    fun deliverNoMatch(sessionId: Long) {
        val listener = synchronized(this) {
            val session = activeSession ?: return
            if (session.id != sessionId) {
                return
            }
            val callback = activeListener
            activeSession = null
            activeListener = null
            callback
        }
        mainHandler.post {
            listener?.onNoMatch(sessionId)
        }
    }

    fun deliverCancelled(sessionId: Long) {
        val listener = synchronized(this) {
            val session = activeSession ?: return
            if (session.id != sessionId) {
                return
            }
            val callback = activeListener
            activeSession = null
            activeListener = null
            callback
        }
        mainHandler.post {
            listener?.onCancelled(sessionId)
        }
    }

    fun deliverError(sessionId: Long, statusResId: Int) {
        val listener = synchronized(this) {
            val session = activeSession ?: return
            if (session.id != sessionId) {
                return
            }
            val callback = activeListener
            activeSession = null
            activeListener = null
            callback
        }
        mainHandler.post {
            listener?.onError(sessionId, statusResId)
        }
    }
}
