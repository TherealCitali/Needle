package dev.citali.needle.pilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.citali.needle.pilot.agent.Action
import dev.citali.needle.pilot.agent.AgentEngine
import dev.citali.needle.pilot.overlay.PilotQuestionOverlay
import dev.citali.needle.pilot.overlay.PilotOverlay
import java.util.Locale

/**
 * Observes the accessible UI and executes exactly one validated action at a time.
 *
 * The service only exposes redacted snapshots via [SnapshotBuilder] and keeps a
 * single process-wide [instance] so the agent engine can drive it. It never
 * writes raw event text anywhere.
 */
class NeedleAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NeedleAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: PilotOverlay? = null
    private var questionOverlay: PilotQuestionOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        instance = this
        AgentEngine.onServiceConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The agent loop re-captures the tree on its own cadence. Events are
        // intentionally not logged: raw event text may contain personal data.
    }

    override fun onInterrupt() {
        AgentEngine.onInterrupted()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hidePilotQuestionOverlay()
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hidePilotQuestionOverlay()
        hideOverlay()
        if (instance === this) instance = null
        AgentEngine.onServiceConnected(false)
        super.onDestroy()
    }

    // ---- Snapshotting -----------------------------------------------------

    fun captureSnapshot(redact: Boolean = true): UiSnapshot =
        SnapshotBuilder.build(currentRoot(), redact)

    private fun currentRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
    }

    // ---- Node resolution --------------------------------------------------

    /** Resolves an action target: "#12" means node id 12, otherwise a text / desc / resource-id match. */
    fun resolveNode(target: String): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        val walk = SnapshotBuilder.walkDfs(root)
        if (target.startsWith("#")) {
            val index = target.substring(1).toIntOrNull() ?: return null
            return walk.getOrNull(index)?.first
        }
        val q = target.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        for ((info, _) in walk) {
            val resId = info.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT)
            if (resId != null && (resId == q || resId.endsWith(q))) return info
        }
        for ((info, _) in walk) {
            if (info.text?.toString()?.contains(target, ignoreCase = true) == true) return info
            if (info.contentDescription?.toString()?.contains(target, ignoreCase = true) == true) return info
        }
        return null
    }

    /** Human-readable description of a target, used by the safety policy. */
    fun describeNode(target: String): String {
        val node = resolveNode(target) ?: return target
        val parts = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add("\"${it.take(40)}\"") }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add("desc=\"${it.take(40)}\"") }
        node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { parts.add("id=$it") }
        node.className?.toString()?.substringAfterLast('.')?.let { parts.add(it) }
        return parts.joinToString(" ")
    }

    // ---- Action execution -------------------------------------------------

    /** Executes a physical action. Returns true if it was dispatched successfully. */
    fun execute(action: Action): Boolean = when (action) {
        is Action.Tap -> clickTarget(action.target, long = false)
        is Action.LongPress -> clickTarget(action.target, long = true)
        is Action.Swipe -> swipe(action.direction)
        is Action.Type -> typeInto(action.target, action.text)
        is Action.Key -> globalKey(action.key)
        is Action.OpenApp -> launchApp(action.packageName)
        is Action.Wait, is Action.AskUser, is Action.Complete, is Action.Fail -> false
    }

    private fun clickTarget(target: String, long: Boolean): Boolean {
        val node = resolveNode(target) ?: return false
        return clickNode(node, long)
    }

    private fun clickNode(info: AccessibilityNodeInfo, long: Boolean): Boolean {
        val actionId = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (info.performAction(actionId)) return true
        val bounds = Rect()
        info.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            tapAt(bounds.exactCenterX(), bounds.exactCenterY(), if (long) 600L else 60L)
            return true
        }
        return false
    }

    private fun typeInto(target: String, text: String): Boolean {
        val node = resolveNode(target) ?: return false
        if (!node.isEditable) return false
        // Focus first: many apps only wire up their IME action (and their search
        // suggestions) once the field actually has input focus.
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun globalKey(key: Action.KeyAction): Boolean = when (key) {
        Action.KeyAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        Action.KeyAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        Action.KeyAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        Action.KeyAction.ENTER -> imeEnter()
    }

    /**
     * Submit the current text field.
     *
     * Order matters. The previous implementation scanned the *app* window first
     * for anything labelled "search", which in most apps matches the search box
     * itself (for example YouTube's `search_edit_text`). Tapping that just
     * re-focuses the field, so the query was typed and never submitted while the
     * action still reported success.
     *
     * Now: ask the IME to submit via the focused editable node (the only reliable
     * path), then the on-screen keyboard's own action key, and only then a real
     * submit button in the app -- explicitly never the text field we typed into.
     */
    private fun imeEnter(): Boolean {
        val focused = focusedEditable()

        // 1. The correct API: tell the IME to perform its editor action.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && focused != null) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }

        // 2. The soft keyboard's action key (labelled Search / Go / Enter / Done).
        val keyLabels = listOf("search", "go", "enter", "done", "send", "submit", "next")
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            for ((info, _) in SnapshotBuilder.walkDfs(window.root)) {
                if (!info.isClickable) continue
                val text = info.text?.toString()?.trim().orEmpty()
                val desc = info.contentDescription?.toString()?.trim().orEmpty()
                if (keyLabels.any { text.equals(it, true) || desc.equals(it, true) }) {
                    if (clickNode(info, long = false)) return true
                }
            }
        }

        // 3. A genuine submit control in the app -- never an editable field, and
        //    never the node we just typed into.
        val focusedId = focused?.viewIdResourceName
        for ((info, _) in SnapshotBuilder.walkDfs(currentRoot())) {
            if (!info.isClickable || info.isEditable) continue
            if (focusedId != null && info.viewIdResourceName == focusedId) continue
            val text = info.text?.toString()?.trim().orEmpty()
            val desc = info.contentDescription?.toString()?.trim().orEmpty()
            val submitLabels = listOf("search", "go", "submit", "done")
            if (submitLabels.any { text.equals(it, true) || desc.equals(it, true) }) {
                if (clickNode(info, long = false)) return true
            }
        }
        return false
    }

    /** The editable node that currently has input focus, if any. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        currentRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }?.let { return it }
        return SnapshotBuilder.walkDfs(currentRoot())
            .firstOrNull { (info, _) -> info.isEditable && info.isFocused }?.first
            ?: SnapshotBuilder.walkDfs(currentRoot())
                .firstOrNull { (info, _) -> info.isEditable }?.first
    }

    private fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun swipe(direction: Action.SwipeDirection): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction) {
            Action.SwipeDirection.UP -> floatArrayOf(w / 2, h * 0.78f, w / 2, h * 0.28f)
            Action.SwipeDirection.DOWN -> floatArrayOf(w / 2, h * 0.28f, w / 2, h * 0.78f)
            Action.SwipeDirection.LEFT -> floatArrayOf(w * 0.82f, h / 2, w * 0.18f, h / 2)
            Action.SwipeDirection.RIGHT -> floatArrayOf(w * 0.18f, h / 2, w * 0.82f, h / 2)
        }
        gesture(sx, sy, ex, ey, 400L)
        return true
    }

    private fun tapAt(x: Float, y: Float, durationMs: Long) = gesture(x, y, x, y, durationMs)

    private fun gesture(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, mainHandler)
    }

    /**
     * Asks the platform for a screenshot (Android 11+). The capture is written by
     * the system into the Screenshots folder; nothing is read back into the app.
     */
    fun takeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    // ---- Floating Stop control -------------------------------------------

    /**
     * The overlay is a convenience, never a requirement. Every entry point is
     * guarded and marshalled onto the main thread so a windowing failure can
     * degrade to "no floating Stop button" instead of taking down the task.
     */
    fun showOverlay(status: String) = onMain {
        if (overlay == null) overlay = PilotOverlay(this)
        overlay?.show(status)
    }

    fun updateOverlay(status: String) = onMain {
        overlay?.update(status)
    }

    fun hideOverlay() = onMain {
        overlay?.hide()
        overlay = null
    }

    /** Floating question, so a prompt is reachable from any foreground app. */
    fun showPilotQuestionOverlay(question: dev.citali.needle.pilot.agent.AgentEngine.Question) = onMain {
        if (questionOverlay == null) questionOverlay = PilotQuestionOverlay(this)
        questionOverlay?.show(question)
    }

    fun hidePilotQuestionOverlay() = onMain {
        questionOverlay?.hide()
        questionOverlay = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        val guarded = Runnable { runCatching { block() } }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run()
        else mainHandler.post(guarded)
    }
}
