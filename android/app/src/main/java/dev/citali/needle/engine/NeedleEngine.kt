package dev.citali.needle.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The on-device Needle agent.
 *
 * One process-wide engine, one session. The engine owns a single global model
 * that is not thread-safe, so every native call is funnelled through
 * [dispatcher]; a session change (different system prompt or tool catalogue)
 * re-initialises the static prefix without reloading the weights.
 */
object NeedleEngine {

    private const val TAG = "NeedleEngine"

    enum class Status { UNSUPPORTED, MODEL_MISSING, LOADING, READY, ERROR }

    data class State(
        val status: Status = Status.MODEL_MISSING,
        val detail: String = "",
        val engineAvailable: Boolean = false,
        val modelLoaded: Boolean = false,
        val sessionKey: String? = null,
        val tools: List<String> = emptyList(),
        val prefixTokens: Int = 0,
    )

    data class SessionSpec(
        val key: String,
        val systemPrompt: String,
        val tools: List<NeedleTool>,
    )

    data class ToolCall(val name: String, val arguments: JSONObject) {
        fun describe(): String = buildString {
            append(name)
            append('(')
            append(
                arguments.keys().asSequence().joinToString(", ") { key ->
                    val value = arguments.opt(key)?.toString().orEmpty()
                    "$key=${if (value.length > 60) value.take(57) + "…" else value}"
                }
            )
            append(')')
        }
    }

    data class NeedleReply(
        val type: String,
        val reasoning: String?,
        val confidence: Double?,
        val text: String?,
        val calls: List<ToolCall>,
        val results: List<String>,
    ) {
        val percentConfidence: Int? get() = confidence?.let { (it * 100).toInt() }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "needle-engine").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var activeSessionKey: String? = null
    private var activeTools: List<NeedleTool> = emptyList()
    private var modelLoaded = false
    private var prefixTokens = 0

    fun isReady(): Boolean = _state.value.status == Status.READY

    fun isModelLoaded(): Boolean = modelLoaded

    /** Asynchronously pre-loads model weights and default session prefix in background. */
    fun warmup(context: Context) {
        val app = context.applicationContext
        if (!ModelRepository.hasWeights(app) || !runCatching { NeedleNative.nativeEngineAvailable() }.getOrDefault(false)) {
            return
        }
        scope.launch {
            if (!modelLoaded) {
                runCatching {
                    val spec = NeedleSessions.phoneSpec(app)
                    prepare(app, spec)
                }
            }
        }
    }

    /** Re-reads the device state: engine present, weights on disk. */
    fun refresh(context: Context) {
        val available = runCatching { NeedleNative.nativeEngineAvailable() }.getOrDefault(false)
        val hasWeights = ModelRepository.hasWeights(context)
        _state.value = _state.value.copy(
            engineAvailable = available,
            status = when {
                !available -> Status.UNSUPPORTED
                modelLoaded -> Status.READY
                !hasWeights -> Status.MODEL_MISSING
                else -> _state.value.status.takeIf { it == Status.READY } ?: Status.MODEL_MISSING
            },
            detail = when {
                !available -> "This build was compiled without the Needle engine for this CPU."
                !modelLoaded && !hasWeights ->
                    "Download the ${ModelRepository.humanSize(ModelRepository.WEIGHTS_BYTES)} Needle ${dev.citali.needle.BuildConfig.NEEDLE_ENGINE_VERSION} model once, then everything runs offline."
                else -> _state.value.detail
            },
        )
        if (available && hasWeights && !modelLoaded) {
            warmup(context)
        }
    }

    /**
     * Loads the weights (once per process) and initialises the static prefix for
     * [spec]. Safe to call before every turn: a matching session is a no-op.
     */
    suspend fun prepare(context: Context, spec: SessionSpec): Result<Unit> = withContext(dispatcher) {
        runCatching {
            val available = NeedleNative.nativeEngineAvailable()
            if (!available) {
                _state.value = _state.value.copy(status = Status.UNSUPPORTED, engineAvailable = false)
                error("This build has no Needle engine for this device's CPU architecture.")
            }

            if (!modelLoaded) {
                _state.value = _state.value.copy(status = Status.LOADING, detail = "Loading the model…")
                val path = ModelRepository.weightsFile(context)
                if (!path.exists()) {
                    _state.value = _state.value.copy(
                        status = Status.MODEL_MISSING,
                        detail = "The Needle model file is missing.",
                    )
                    error("The Needle model file is missing. Download it in Settings first.")
                }
                val code = NeedleNative.nativeLoadModel(path.absolutePath)
                if (code != 0) {
                    val message = NeedleNative.nativeLastError()
                    _state.value = _state.value.copy(status = Status.ERROR, detail = message)
                    error("The engine could not load the model: $message")
                }
                modelLoaded = true
            }

            if (activeSessionKey != spec.key) {
                val systemPrompt = spec.systemPrompt.trim()
                val toolsJson = NeedleTool.schemaArray(spec.tools)
                val prefix = NeedleNative.nativeInit(systemPrompt, toolsJson)
                if (prefix < 0) {
                    val message = NeedleNative.nativeLastError().ifBlank {
                        "needle_init failed (code $prefix)"
                    }
                    _state.value = _state.value.copy(
                        status = Status.ERROR,
                        detail = message,
                        sessionKey = spec.key,
                        tools = spec.tools.map { it.name },
                    )
                    error(
                        "The system prompt and tools did not fit the model context: $message " +
                            "Turn off a tool pack in Settings to shrink it."
                    )
                }
                activeSessionKey = spec.key
                activeTools = spec.tools
                prefixTokens = prefix
                Log.i(TAG, "session ${spec.key} ready, prefix=$prefix tokens, tools=${spec.tools.size}")
            }

            _state.value = _state.value.copy(
                status = Status.READY,
                detail = "Ready · Needle ${dev.citali.needle.BuildConfig.NEEDLE_ENGINE_VERSION} · " +
                    "${spec.tools.size} tools · static prefix $prefixTokens tokens",
                modelLoaded = true,
                sessionKey = spec.key,
                tools = spec.tools.map { it.name },
                prefixTokens = prefixTokens,
            )
            Unit
        }.onFailure { error ->
            Log.w(TAG, "prepare failed: ${error.message}")
            if (_state.value.status != Status.ERROR && _state.value.status != Status.MODEL_MISSING &&
                _state.value.status != Status.UNSUPPORTED
            ) {
                _state.value = _state.value.copy(status = Status.ERROR, detail = error.message.orEmpty())
            }
        }
    }

    /** One raw completion against the current session. Resets engine state first. */
    suspend fun completeEnvelope(
        context: Context,
        spec: SessionSpec,
        input: String,
        maxNewTokens: Int = 512,
    ): Result<String> = withContext(dispatcher) {
        runCatching {
            prepare(context, spec).getOrThrow()
            NeedleNative.nativeReset()
            val raw = NeedleNative.nativeComplete(input, maxNewTokens)
                ?: error("The engine returned nothing: ${NeedleNative.nativeLastError()}")
            raw
        }
    }

    /**
     * The tool-calling loop: ask, execute whatever the model called, hand the
     * results back, and repeat until it stops calling tools.
     */
    suspend fun run(
        context: Context,
        spec: SessionSpec,
        query: String,
        maxSteps: Int = 4,
        maxNewTokens: Int = 512,
        onToolCall: ((ToolCall) -> Unit)? = null,
    ): Result<NeedleReply> = withContext(dispatcher) {
        runCatching {
            prepare(context, spec).getOrThrow()
            NeedleNative.nativeReset()

            var raw = NeedleNative.nativeComplete(query, maxNewTokens)
                ?: error("The engine returned nothing: ${NeedleNative.nativeLastError()}")

            val executed = mutableListOf<String>()
            var lastCalls = emptyList<ToolCall>()
            var envelope = parseEnvelope(raw)

            for (step in 0 until maxSteps) {
                val calls = envelope.calls
                if (envelope.type != "call" || calls.isEmpty()) break
                lastCalls = calls
                val results = calls.map { call ->
                    onToolCall?.invoke(call)
                    executeTool(call)
                }
                executed += results
                val feedback = JSONArray().apply { results.forEach { put(it) } }.toString()
                raw = NeedleNative.nativeComplete(feedback, maxNewTokens)
                    ?: error("The engine returned nothing while following up on a tool call.")
                envelope = parseEnvelope(raw)
            }

            NeedleReply(
                type = envelope.type,
                reasoning = envelope.reasoning,
                confidence = envelope.confidence,
                text = envelope.text,
                calls = lastCalls,
                results = executed,
            )
        }.onFailure { Log.w(TAG, "run failed: ${it.message}") }
    }

    private suspend fun executeTool(call: ToolCall): String {
        val tool = activeTools.firstOrNull { it.name == call.name }
            ?: return JSONObject().put("error", "unknown tool: ${call.name}").toString()
        return runCatching { tool.handler(call.arguments) }
            .getOrElse { error -> JSONObject().put("error", error.message ?: "tool failed").toString() }
    }

    internal data class Envelope(
        val type: String,
        val reasoning: String?,
        val confidence: Double?,
        val text: String?,
        val calls: List<ToolCall>,
    )

    internal fun parseEnvelope(raw: String): Envelope {
        val json = runCatching { JSONObject(raw) }.getOrElse { error ->
            error("The engine returned an unparseable envelope: ${error.message}")
        }
        val calls = mutableListOf<ToolCall>()
        val array = json.optJSONArray("function_calls")
        if (array != null) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val name = entry.optString("name")
                if (name.isBlank()) continue
                val arguments = entry.optJSONObject("arguments") ?: JSONObject()
                calls += ToolCall(name, arguments)
            }
        }
        val confidence = if (json.has("confidence") && !json.isNull("confidence")) {
            json.optDouble("confidence").takeIf { !it.isNaN() }
        } else {
            null
        }
        val text = listOf("content", "answer", "text", "message", "response")
            .asSequence()
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            .firstOrNull()
        return Envelope(
            type = json.optString("type").ifBlank { if (calls.isEmpty()) "answer" else "call" },
            reasoning = json.optString("reasoning").takeIf { it.isNotBlank() },
            confidence = confidence,
            text = text,
            calls = calls,
        )
    }

    /** A short fact block the model can rely on for relative dates. */
    fun dateFact(): String =
        "Today is " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + "."

    /** Drops the loaded model (used when the weights are replaced or deleted). */
    suspend fun unload() = withContext(dispatcher) {
        runCatching {
            if (modelLoaded) {
                NeedleNative.nativeUnload()
            }
        }
        modelLoaded = false
        activeSessionKey = null
        activeTools = emptyList()
        prefixTokens = 0
        _state.value = _state.value.copy(
            status = Status.MODEL_MISSING,
            detail = "The model is not loaded.",
            modelLoaded = false,
            sessionKey = null,
            prefixTokens = 0,
        )
        Unit
    }
}
