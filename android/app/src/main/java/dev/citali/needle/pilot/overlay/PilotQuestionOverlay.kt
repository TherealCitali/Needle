package dev.citali.needle.pilot.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
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
 * Floating question prompt shown while a task is waiting on the user.
 *
 * The in-app AlertDialog could only be seen from inside TaskPilot. Because the
 * agent drives *other* apps, the question was raised while a different app was
 * in the foreground, so it was invisible until the user happened to switch back
 * -- and to the user the task simply looked stalled and then abandoned.
 *
 * This renders the same question as a TYPE_ACCESSIBILITY_OVERLAY window, so it
 * appears above whatever app is on screen. It owns its own lifecycle for the
 * same reason [PilotOverlay] does: a ComposeView outside an Activity has no
 * view-tree owners and will throw during its first traversal without them.
 */
class PilotQuestionOverlay(private val service: AccessibilityService) :
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

    init {
        savedStateController.performRestore(null)
    }

    fun show(question: AgentEngine.Question) {
        hide()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@PilotQuestionOverlay)
            setViewTreeViewModelStoreOwner(this@PilotQuestionOverlay)
            setViewTreeSavedStateRegistryOwner(this@PilotQuestionOverlay)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NeedleTheme {
                    QuestionContent(
                        question = question,
                        onApprove = { text -> AgentEngine.answer(true, text) },
                        onDecline = { AgentEngine.answer(false) },
                    )
                }
            }
        }

        // Focusable: the user may need to type an answer, which requires the IME.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.55f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching { windowManager.addView(composeView, params) }
            .onSuccess {
                view = composeView
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
            .onFailure {
                // If the window cannot be shown the in-app dialog still exists.
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
    }

    fun hide() {
        val current = view
        view = null
        if (current != null) {
            runCatching { lifecycleRegistry.currentState = Lifecycle.State.CREATED }
            runCatching { windowManager.removeView(current) }
        }
        runCatching { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
        runCatching { store.clear() }
    }
}

@Composable
private fun QuestionContent(
    question: AgentEngine.Question,
    onApprove: (String?) -> Unit,
    onDecline: () -> Unit,
) {
    var answerText by remember(question) { mutableStateOf("") }
    Surface(
        modifier = Modifier.padding(20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (question.highRisk) "Confirmation required" else "TaskPilot needs an answer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (question.highRisk) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = question.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!question.highRisk) {
                OutlinedTextField(
                    value = answerText,
                    onValueChange = { answerText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your answer") },
                    minLines = 2,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDecline) {
                    Text(if (question.highRisk) "Decline" else "Cancel task")
                }
                Button(onClick = { onApprove(answerText.takeIf { it.isNotBlank() }) }) {
                    Text(if (question.highRisk) "Approve" else "Continue")
                }
            }
        }
    }
}
