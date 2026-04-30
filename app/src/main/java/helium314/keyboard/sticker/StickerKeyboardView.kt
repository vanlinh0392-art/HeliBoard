package helium314.keyboard.sticker

import android.content.Intent
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.inputmethodservice.InputMethodService
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.KeyVisualAttributes

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsActivity2
import helium314.keyboard.settings.SettingsDestination

/**
 * View that displays sticker picker panel in keyboard.
 * Similar to EmojiPalettesView but uses Compose UI.
 */
class StickerKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private var stickerManagerState by mutableStateOf(StickerManager(context))
    private val stickerSender = StickerSender(context)
    private var editorInfo: EditorInfo? = null
    private var sendScope: CoroutineScope? = null
    private var sendJob: Job? = null
    
    init {
        setupComposeView()
    }
    
    private var composeLifecycleOwner: ComposeLifecycleOwner? = null

    private fun setupComposeView() {
        val composeView = ComposeView(context)
        composeView.setContent {
            StickerKeyboardContent()
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        // Create lifecycle owner
        val lifecycleOwner = ComposeLifecycleOwner()
        composeLifecycleOwner = lifecycleOwner
        
        // Attach to THIS view (direct parent of ComposeView)
        this.setViewTreeLifecycleOwner(lifecycleOwner)
        this.setViewTreeViewModelStoreOwner(lifecycleOwner)
        this.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        
        // Also attach to the ROOT view of the window to ensure WindowRecomposer finds it (fallback)
        val root = this.rootView
        if (root != null && root != this) {
            root.setViewTreeLifecycleOwner(lifecycleOwner)
            root.setViewTreeViewModelStoreOwner(lifecycleOwner)
            root.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        
        // Start lifecycle
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()
        sendScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromWindow() {
        cancelPendingStickerSend()
        sendScope?.cancel()
        sendScope = null
        super.onDetachedFromWindow()
        composeLifecycleOwner?.onDestroy()
        composeLifecycleOwner = null
    }

    @Composable
    private fun StickerKeyboardContent() {
        // Main sticker picker panel
            StickerPickerPanel(
            stickerManager = stickerManagerState,
            onStickersSelected = { stickers, sendMode ->
                sendStickers(stickers, sendMode)
            },
            onBackClick = {
                // Return to main keyboard
                keyboardActionListener.onCodeInput(
                    KeyCode.ALPHA,
                    NOT_A_COORDINATE,
                    NOT_A_COORDINATE,
                    false
                )
            },
            onAddPackClick = {
                launchStickerSettings()
            },
            onSettingsClick = {
                launchStickerSettings()
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    
    private fun sendStickers(stickers: List<Sticker>, sendMode: StickerSendMode) {
        if (stickers.isEmpty()) return
        val scope = sendScope ?: return

        cancelPendingStickerSend()
        sendJob = scope.launch {
            for ((index, sticker) in stickers.withIndex()) {
                // Add to recent
                stickerManagerState.addToRecent(sticker)
                
                // Send sticker via callback
                keyboardActionListener.onSendSticker(sticker)
                
                if (index < stickers.size - 1) {
                    when (sendMode) {
                        StickerSendMode.SEND_ALL_NOW -> delay(100)
                        StickerSendMode.SHOW_ONE_BY_ONE -> delay(2000)
                    }
                }
            }
            
            // Signal back to keyboard to return to main keyboard after all sent
            keyboardActionListener.onCodeInput(
                KeyCode.ALPHA,
                NOT_A_COORDINATE,
                NOT_A_COORDINATE,
                false
            )
        }
    }
    
    // ========== Public API ==========
    
    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (enabled) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }
    
    fun startStickerPalettes(
        keyVisualAttr: KeyVisualAttributes?,
        editorInfo: EditorInfo?,
        keyboardActionListener: KeyboardActionListener
    ) {
        clearKeyboardCache()
        this.editorInfo = editorInfo
        this.keyboardActionListener = keyboardActionListener
    }
    
    fun stopStickerPalettes() {
        cancelPendingStickerSend()
    }
    
    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }
    
    fun clearKeyboardCache() {
        cancelPendingStickerSend()
        // Reload sticker packs
        stickerManagerState = StickerManager(context)
    }

    private fun cancelPendingStickerSend() {
        sendJob?.cancel()
        sendJob = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == View.VISIBLE) {
            clearKeyboardCache()
        }
    }
    
    /**
     * Get the StickerManager for external access (e.g., from LatinIME)
     */
    fun getStickerManager(): StickerManager = stickerManagerState
    
    /**
     * Get the StickerSender for external access
     */
    fun getStickerSender(): StickerSender = stickerSender

    private fun launchStickerSettings() {
        runCatching {
            (context as? InputMethodService)?.requestHideSelf(0)
            SettingsDestination.navigateTo(SettingsDestination.Stickers)
            val intent = Intent(context, SettingsActivity2::class.java).apply {
                putExtra(SettingsActivity.EXTRA_START_DESTINATION, SettingsDestination.Stickers)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private class ComposeLifecycleOwner : 
        androidx.lifecycle.LifecycleOwner, 
        androidx.lifecycle.ViewModelStoreOwner, 
        androidx.savedstate.SavedStateRegistryOwner {

        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
        private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
        private val store = androidx.lifecycle.ViewModelStore()

        override val lifecycle: androidx.lifecycle.Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: androidx.lifecycle.ViewModelStore
            get() = store

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        }

        fun onStart() {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
