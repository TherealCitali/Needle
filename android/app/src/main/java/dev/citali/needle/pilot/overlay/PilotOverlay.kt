package dev.citali.needle.pilot.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.citali.needle.pilot.agent.AgentEngine
import dev.citali.needle.ui.theme.NeedleTheme

/**
 * Floating Stop control shown while a task is running.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which is available to an enabled
 * AccessibilityService without the "display over other apps" permission.
 *
 * A ComposeView attached outside an Activity has no view-tree owners of its own.
 * Compose resolves those owners lazily, on the first window traversal *after*
 * `addView` returns, so a missing owner surfaces as an uncaught
 * IllegalStateException on the main looper rather than as a catchable failure at
 * the call site. This class therefore acts as the lifecycle, saved-state, and
 * view-model store owner for its own overlay window.
 */
class PilotOverlay(private val service: AccessibilityService) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null
    private val statusState = mutableStateOf("Running")

    init {
        savedStateController.performRestore(null)
    }

    fun show(statusText: String) {
        statusState.value = statusText

        // Only skip re-adding when the window is genuinely still attached.
        // Checking `view != null` alone was not enough: the system can detach
        // the overlay (service rebind, display change, OEM window cleanup) while
        // the reference stays non-null, and the pill would never come back.
        view?.let { existing ->
            if (existing.isAttachedToWindow) return
            runCatching { windowManager.removeView(existing) }
            view = null
        }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@PilotOverlay)
            setViewTreeViewModelStoreOwner(this@PilotOverlay)
            setViewTreeSavedStateRegistryOwner(this@PilotOverlay)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NeedleTheme {
                    OverlayContent(statusState = statusState, onStop = { AgentEngine.stop() })
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 24.dpToPx()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching { windowManager.addView(composeView, params) }
            .onSuccess {
                view = composeView
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
            .onFailure {
                // Overlay is best-effort; the in-app live log still shows the Stop control.
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
    }

    fun update(statusText: String) {
        statusState.value = statusText
    }

    fun hide() {
        val current = view
        view = null
        if (current != null) {
            // Move to CREATED first so the composition tears down cleanly, then
            // detach, then finish the lifecycle.
            runCatching { lifecycleRegistry.currentState = Lifecycle.State.CREATED }
            runCatching { windowManager.removeView(current) }
        }
        runCatching { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
        runCatching { store.clear() }
    }

    private fun Int.dpToPx(): Int =
        (this * service.resources.displayMetrics.density).toInt()
}

@Composable
private fun OverlayContent(statusState: State<String>, onStop: () -> Unit) {
    val status by statusState
    // The overlay floats over whatever app the task is driving, so it stays a
    // compact pill: capped width, single line, and a small icon-only Stop.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 260.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Surface(
                onClick = onStop,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = CircleShape,
                modifier = Modifier.size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Stop the running task",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
