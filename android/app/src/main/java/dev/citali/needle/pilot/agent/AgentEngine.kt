package dev.citali.needle.pilot.agent

import android.content.Context
import dev.citali.needle.pilot.accessibility.NeedleAccessibilityService
import dev.citali.needle.pilot.data.AppInventory
import dev.citali.needle.pilot.data.HistoryStore
import dev.citali.needle.pilot.data.SecureStore
import dev.citali.needle.engine.EngineBrain
import dev.citali.needle.pilot.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * The one-action control loop: observe the UI, think of one action, validate it
 * against the safety policy, execute it, then observe again.
 *
 * State is exposed through [state] so the Compose UI and the floating overlay
 * can render the same truth.
 */
object AgentEngine {

    enum class Phase { IDLE, RUNNING, WAITING_FOR_USER, PAUSED, BLOCKED, COMPLETED, FAILED, STOPPED }

    enum class LogLevel { INFO, ACTION, MODEL, WARN, ERROR }

    data class LogEntry(val timeMillis: Long, val level: LogLevel, val message: String)

    data class Question(val text: String, val highRisk: Boolean)

    data class EngineState(
        val phase: Phase = Phase.IDLE,
        val command: String = "",
        val statusText: String = "Ready",
        val log: List<LogEntry> = emptyList(),
        val question: Question? = null,
        val serviceConnected: Boolean = NeedleAccessibilityService.isConnected(),
    )

    private data class QuestionAnswer(val approved: Boolean, val text: String?)

    private sealed interface ExecResult {
        object Ok : ExecResult
        data class Complete(val summary: String) : ExecResult
        data class Fail(val reason: String) : ExecResult
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    /**
     * Last-resort net. The loop already handles its own failures, but anything
     * that escapes a coroutine on this scope would otherwise reach the default
     * uncaught handler and kill the process mid-task. Surface it as a failed
     * run instead.
     */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        if (error is CancellationException) return@CoroutineExceptionHandler
        runCatching {
            log(LogLevel.ERROR, "Unexpected error: ${error.message ?: error::class.java.simpleName}")
            finish(Phase.FAILED, "Needle stopped after an unexpected error.")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    private var job: Job? = null

    @Volatile
    private var paused = false

    private var aiFailures = 0
    private var aiAnnounced = false
    private var activeModel: String? = null
    private var localFailures = 0
    private var localAnnounced = false

    private var pendingQuestion: CompletableDeferred<QuestionAnswer>? = null

    fun isActive(): Boolean = job?.isActive == true

    fun start(context: Context, plan: Plan) {
        if (isActive()) cancelJob()
        paused = false
        aiFailures = 0
        aiAnnounced = false
        localFailures = 0
        localAnnounced = false
        activeModel = null
        pendingQuestion = null
        _state.value = EngineState(
            phase = Phase.RUNNING,
            command = plan.command,
            statusText = "Starting…",
            serviceConnected = NeedleAccessibilityService.isConnected(),
        )
        log(LogLevel.INFO, "Task approved: ${plan.command}")
        plan.steps.forEach { step ->
            log(LogLevel.INFO, "• ${step.title}" + if (step.highRisk) "  [high-risk]" else "")
        }
        job = scope.launch { runLoop(context.applicationContext, plan) }
    }

    /** Clears a finished run so the UI returns to its idle state. */
    fun reset() {
        if (isActive()) return
        _state.value = EngineState(serviceConnected = NeedleAccessibilityService.isConnected())
    }

    fun stop() {
        if (!isActive()) return
        log(LogLevel.WARN, "Stop requested.")
        finish(Phase.STOPPED, "Stopped by user.")
        cancelJob()
    }

    fun pause() {
        if (!isActive()) return
        paused = true
        _state.value = _state.value.copy(phase = Phase.PAUSED, statusText = "Paused")
    }

    fun resume() {
        paused = false
        if (job?.isActive == true && _state.value.phase == Phase.PAUSED) {
            _state.value = _state.value.copy(phase = Phase.RUNNING, statusText = "Resuming…")
        }
    }

    fun answer(approved: Boolean, text: String? = null) {
        val deferred = pendingQuestion ?: return
        if (deferred.isCompleted) return
        pendingQuestion = null
        _state.value = _state.value.copy(question = null, statusText = "Continuing…")
        deferred.complete(QuestionAnswer(approved, text))
    }

    fun onServiceConnected(connected: Boolean) {
        _state.value = _state.value.copy(serviceConnected = connected)
        if (!connected && isActive()) {
            log(LogLevel.ERROR, "Accessibility service disconnected.")
            finish(Phase.BLOCKED, "Re-enable Needle automation in Accessibility settings.")
            cancelJob()
        }
    }

    fun onInterrupted() {
        if (isActive()) {
            log(LogLevel.WARN, "System interrupted the accessibility service.")
            pause()
        }
    }

    // ---- Loop -------------------------------------------------------------

    private suspend fun runLoop(context: Context, plan: Plan) {
        val settings = SettingsStore.snapshot(context)
        val deterministic = DeterministicExecutor(plan.intent)
        val recent = ArrayDeque<String>()
        var consecutiveFailures = 0
        var lastSignature: String? = null
        var stuckCount = 0
        var highRiskApprovedFor: String? = null

        if (settings.showOverlay) {
            NeedleAccessibilityService.instance?.showOverlay("Running")
        }

        try {
            while (coroutineContext.isActive) {
                while (paused && coroutineContext.isActive) {
                    // Keep the pill up while paused, otherwise the only Stop
                    // control disappears exactly when the user wants it.
                    if (settings.showOverlay) {
                        NeedleAccessibilityService.instance?.showOverlay("Paused")
                    }
                    delay(250)
                }

                val service = NeedleAccessibilityService.instance
                if (service == null) {
                    finish(Phase.BLOCKED, "Enable Needle automation in Accessibility settings, then run the task again.")
                    record(context, plan.command, "blocked")
                    return
                }

                val snapshot = runCatching { service.captureSnapshot(settings.redactSensitiveValues) }
                    .onFailure { log(LogLevel.WARN, "Could not read the screen; retrying.") }
                    .getOrNull()
                if (snapshot == null) {
                    delay(600)
                    continue
                }
                if (settings.showTreeSummary) {
                    log(
                        LogLevel.INFO,
                        "Tree: ${snapshot.nodeCount} nodes in ${snapshot.packageName ?: "unknown"} (redacted)."
                    )
                }

                // Resolve app targets against the real installed-app list before
                // anything else looks at the action. A model can hallucinate a
                // package name; launching it would just fail silently, so correct
                // it here or turn it into a Fail with a clear reason.
                val action = resolveAppAction(
                    context,
                    nextAction(context, settings, plan, snapshot, recent, deterministic),
                )

                when (action) {
                    is Action.Complete -> {
                        log(LogLevel.INFO, "Done: ${action.summary}")
                        finish(Phase.COMPLETED, action.summary)
                        record(context, plan.command, "completed")
                        return
                    }
                    is Action.Fail -> {
                        log(LogLevel.ERROR, action.reason)
                        finish(Phase.FAILED, action.reason)
                        record(context, plan.command, "failed")
                        return
                    }
                    else -> Unit
                }

                // Validate against the safety policy before executing.
                val targetDescription = action.nodeTarget?.let { service.describeNode(it) }
                val decision = SafetyPolicy.assess(action, targetDescription)
                if (settings.showValidation) {
                    log(LogLevel.INFO, "Validation [${decision.level}]: ${decision.reason}")
                }
                when (decision.level) {
                    RiskLevel.BLOCKED -> {
                        log(LogLevel.ERROR, decision.reason)
                        finish(Phase.BLOCKED, decision.reason)
                        record(context, plan.command, "blocked")
                        return
                    }
                    RiskLevel.HIGH_RISK -> {
                        val key = actionKey(action)
                        if (settings.highRiskConfirmations && highRiskApprovedFor != key) {
                            val answer = awaitAnswer(
                                Question(
                                    "${decision.reason}\n\n${describeAction(action)}",
                                    highRisk = true,
                                )
                            )
                            if (!answer.approved) {
                                log(LogLevel.WARN, "High-risk action declined; stopping.")
                                finish(Phase.STOPPED, "High-risk action declined.")
                                record(context, plan.command, "stopped")
                                return
                            }
                            highRiskApprovedFor = key
                            log(LogLevel.INFO, "High-risk action approved for this step.")
                        }
                    }
                    else -> Unit
                }

                if (action is Action.AskUser) {
                    val answer = awaitAnswer(Question(action.question, highRisk = false))
                    if (!answer.approved) {
                        log(LogLevel.WARN, "Question declined; stopping.")
                        finish(Phase.STOPPED, "Task cancelled.")
                        record(context, plan.command, "stopped")
                        return
                    }
                    answer.text?.takeIf { it.isNotBlank() }?.let {
                        deterministic.onUserAnswer(it)
                        recent.add("user answered: ${it.take(80)}")
                    }
                    continue
                }

                if (action is Action.Wait) {
                    delay(action.millis.coerceIn(100L, 5000L))
                    continue
                }

                // Execute exactly one action.
                val description = describeAction(action)
                log(LogLevel.ACTION, description)
                recent.add(description)
                val ok = runCatching { service.execute(action) }
                    .onFailure { log(LogLevel.WARN, "Action threw: ${it.message}") }
                    .getOrDefault(false)
                if (!ok) {
                    consecutiveFailures++
                    log(LogLevel.WARN, "Action did not succeed ($consecutiveFailures/5).")
                } else {
                    consecutiveFailures = 0
                }
                if (consecutiveFailures >= 5) {
                    log(LogLevel.ERROR, "Five consecutive failures; stopping to avoid a loop.")
                    finish(Phase.FAILED, "Five consecutive failed actions.")
                    record(context, plan.command, "failed")
                    return
                }

                // Stuck-screen detection.
                val signature = snapshot.signature
                if (signature == lastSignature) {
                    stuckCount++
                } else {
                    stuckCount = 0
                    lastSignature = signature
                }
                if (stuckCount >= 3) {
                    val answer = awaitAnswer(
                        Question(
                            "The screen does not seem to change after repeated actions. Continue anyway?",
                            highRisk = false,
                        )
                    )
                    if (!answer.approved) {
                        finish(Phase.STOPPED, "Stopped because the screen appeared stuck.")
                        record(context, plan.command, "stopped")
                        return
                    }
                    stuckCount = 0
                }

                if (settings.showOverlay) {
                    // show() is idempotent and re-adds the window if the system
                    // tore it down (service rebind, config change, OEM cleanup).
                    // A single show() at start-up silently vanished mid-task.
                    service.showOverlay(shortStatus(action))
                }
                delay(850)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log(LogLevel.ERROR, "Unexpected error: ${t.message}")
            finish(Phase.FAILED, "Unexpected error: ${t.message}")
            record(context, plan.command, "failed")
        } finally {
            NeedleAccessibilityService.instance?.hideOverlay()
        }
    }

    private suspend fun nextAction(
        context: Context,
        settings: dev.citali.needle.pilot.data.PilotSettings,
        plan: Plan,
        snapshot: dev.citali.needle.pilot.accessibility.UiSnapshot,
        recent: List<String>,
        deterministic: DeterministicExecutor,
    ): Action {
        // 1. The on-device Needle model decides the next action when its weights
        //    are loaded. Everything it proposes still goes through SafetyPolicy.
        if (settings.useOnDeviceModel && EngineBrain.isReady()) {
            if (!localAnnounced) {
                localAnnounced = true
                log(LogLevel.INFO, "Thinking on-device with Needle ${EngineBrain.engineVersion()}…")
            }
            val result = EngineBrain.proposeAction(context, plan, snapshot, recent)
            val action = result.getOrNull()?.let { raw ->
                log(LogLevel.MODEL, "Needle: ${raw.trim().replace('\n', ' ').take(160)}")
                LlmClient.parseAction(raw)
            }
            if (action != null) {
                localFailures = 0
                return action
            }
            localFailures++
            result.exceptionOrNull()?.message?.let { log(LogLevel.WARN, "On-device model: $it") }
            if (localFailures >= 3) {
                return Action.Fail(
                    "The on-device model replied three times in a form the app could not use. " +
                        "Try a shorter command, or configure an AI provider in Settings."
                )
            }
            return Action.Wait(600)
        }

        val apiKey = SecureStore.getApiKey(context)
        val aiConfigured = !apiKey.isNullOrBlank() &&
            settings.endpointUrl.isNotBlank() &&
            settings.model.isNotBlank()

        if (aiConfigured) {
            val chain = settings.modelChain.ifEmpty { listOf(settings.model) }
            if (!aiAnnounced) {
                aiAnnounced = true
                log(LogLevel.INFO, "Asking ${chain.first()} what to do next…")
                if (chain.size > 1) {
                    log(LogLevel.INFO, "Fallback models: ${chain.drop(1).joinToString(", ")}")
                }
            }
            val messages = listOf(
                LlmClient.systemPrompt(),
                LlmClient.userPrompt(
                    plan.command,
                    plan.steps,
                    snapshot.toPromptString(),
                    recent,
                    plan.subGoals,
                    AppInventory.promptListing(context),
                ),
            )
            // Try each model in order; a model that is rate-limited, unavailable,
            // or unknown to the provider should not end the run when the user has
            // named alternatives.
            var result: Result<String> = Result.failure(IllegalStateException("No model configured"))
            for ((index, candidate) in chain.withIndex()) {
                result = LlmClient.complete(
                    settings.endpointUrl,
                    candidate,
                    apiKey!!,
                    messages,
                    settings.apiPath,
                )
                if (result.isSuccess) {
                    if (candidate != activeModel) {
                        activeModel = candidate
                        if (index > 0) log(LogLevel.INFO, "Using fallback model: $candidate")
                    }
                    break
                }
                if (index < chain.lastIndex) {
                    log(
                        LogLevel.WARN,
                        "$candidate failed (${result.exceptionOrNull()?.message?.take(90)}); trying ${chain[index + 1]}."
                    )
                }
            }
            result.onSuccess { raw ->
                val snippet = raw.trim().replace('\n', ' ').take(160)
                log(LogLevel.MODEL, "Model: $snippet")
                val action = LlmClient.parseAction(raw)
                if (action != null) {
                    aiFailures = 0
                    return action
                }
                aiFailures++
                log(LogLevel.WARN, "Model reply could not be parsed (attempt $aiFailures/3).")
            }.onFailure { error ->
                aiFailures++
                log(LogLevel.ERROR, "AI provider error (attempt $aiFailures/3): ${error.message}")
            }

            // The AI is the engine for open-ended tasks. Rather than quietly
            // dropping to the offline executor -- which made a broken provider
            // look like a working app that only understood the examples -- stop
            // and say what went wrong.
            if (aiFailures >= 3) {
                return Action.Fail(
                    "The AI provider is not responding correctly after 3 attempts. " +
                        "Check the base URL, model name, and API key in Settings."
                )
            }
            return Action.Wait(700)
        }

        // No provider configured. The built-in executor only covers a few known
        // routines, so be explicit rather than appearing to fail at random.
        if (plan.intent is TaskIntent.Generic) {
            return Action.Fail(
                "This task needs an AI provider. Add an OpenAI-compatible base URL, " +
                    "model, and API key in Settings, then run it again."
            )
        }
        return deterministic.next(snapshot)
    }

    // ---- Helpers ----------------------------------------------------------

    private suspend fun awaitAnswer(question: Question): QuestionAnswer {
        val deferred = CompletableDeferred<QuestionAnswer>()
        pendingQuestion = deferred
        _state.value = _state.value.copy(
            phase = Phase.WAITING_FOR_USER,
            question = question,
            statusText = "Waiting for your decision",
        )
        // Ask through the overlay so the question is reachable from whatever app
        // is in the foreground, not only from inside Needle.
        NeedleAccessibilityService.instance?.showPilotQuestionOverlay(question)
        try {
            return deferred.await()
        } finally {
            NeedleAccessibilityService.instance?.hidePilotQuestionOverlay()
        }
    }

    /**
     * Checks an [Action.OpenApp] against the device's real package list.
     *
     * Models guess package names when they do not know one -- "dev.citali.brevent"
     * was derived from TaskPilot's own application id. If the stated package is
     * not installed, try to resolve it from the label instead, and only then give
     * up with an explanation.
     */
    private fun resolveAppAction(context: Context, action: Action): Action {
        if (action !is Action.OpenApp) return action
        val byPackage = AppInventory.all(context)
            .firstOrNull { it.packageName.equals(action.packageName, ignoreCase = true) }
        val app = byPackage
            ?: AppInventory.resolve(context, action.label.ifBlank { action.packageName })
            ?: AppInventory.resolve(context, action.packageName.substringAfterLast('.'))

        if (app == null) {
            val name = action.label.ifBlank { action.packageName }
            return Action.Fail("\"$name\" is not installed on this device.")
        }
        if (!app.enabled) {
            return Action.Fail(
                "${app.label} (${app.packageName}) is disabled, so it cannot be opened. " +
                    "Enable it in Android Settings and run the task again."
            )
        }
        if (!app.packageName.equals(action.packageName, ignoreCase = true)) {
            log(LogLevel.WARN, "Corrected package: ${action.packageName} -> ${app.packageName}")
        }
        return Action.OpenApp(app.packageName, app.label)
    }

    private fun describeAction(action: Action): String = when (action) {
        is Action.Tap -> "Tap ${action.target}" + action.description.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        is Action.LongPress -> "Long-press ${action.target}"
        is Action.Swipe -> "Swipe ${action.direction.name.lowercase()}"
        is Action.Type -> "Type ${action.text.length} character(s) into ${action.target}"
        is Action.Key -> "Press ${action.key.name.lowercase()}"
        is Action.OpenApp -> "Open ${action.label.ifBlank { action.packageName }}"
        is Action.Wait -> "Wait ${action.millis} ms"
        is Action.AskUser -> "Ask: ${action.question.take(60)}"
        is Action.Complete -> "Complete: ${action.summary.take(60)}"
        is Action.Fail -> "Fail: ${action.reason.take(60)}"
    }

    /** Very short label for the floating pill, which must stay narrow. */
    private fun shortStatus(action: Action): String = when (action) {
        is Action.Tap -> "Tapping"
        is Action.LongPress -> "Holding"
        is Action.Swipe -> "Swiping"
        is Action.Type -> "Typing"
        is Action.Key -> when (action.key) {
            Action.KeyAction.ENTER -> "Submitting"
            Action.KeyAction.BACK -> "Going back"
            Action.KeyAction.HOME -> "Home"
            Action.KeyAction.RECENTS -> "Recents"
        }
        is Action.OpenApp -> "Opening ${action.label.ifBlank { "app" }}"
        is Action.Wait -> "Waiting"
        is Action.AskUser -> "Needs you"
        is Action.Complete -> "Done"
        is Action.Fail -> "Stopped"
    }

    private fun actionKey(action: Action): String = when (action) {
        is Action.Tap -> "tap:${action.target}"
        is Action.LongPress -> "longpress:${action.target}"
        is Action.Type -> "type:${action.target}"
        is Action.Swipe -> "swipe:${action.direction.name}"
        is Action.Key -> "key:${action.key.name}"
        is Action.OpenApp -> "open:${action.packageName}"
        else -> action.javaClass.simpleName
    }

    private fun log(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _state.value = _state.value.copy(
            log = (_state.value.log + entry).takeLast(200),
            statusText = message.take(120),
        )
    }

    private fun finish(phase: Phase, statusText: String) {
        _state.value = _state.value.copy(phase = phase, statusText = statusText, question = null)
        NeedleAccessibilityService.instance?.hideOverlay()
    }

    private fun record(context: Context, command: String, status: String) {
        scope.launch { runCatching { HistoryStore.record(context, command, status) } }
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }
}
