package dev.citali.needle.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Conversation state for the chat screen.
 *
 * Each turn is independent: the engine's context is reset and the last few
 * exchanges are re-sent as plain text, which keeps a small context window
 * predictable instead of letting unrelated history crowd it out.
 */
object ChatController {

    enum class Role { USER, ASSISTANT, SYSTEM }

    data class Message(
        val role: Role,
        val text: String,
        val reasoning: String? = null,
        val confidence: Int? = null,
        val tools: List<String> = emptyList(),
        val results: List<String> = emptyList(),
        val error: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val history = ArrayDeque<Pair<String, String>>()

    fun send(context: Context, raw: String) {
        val query = raw.trim()
        if (query.isEmpty() || _busy.value) return
        val app = context.applicationContext
        _messages.value = _messages.value + Message(Role.USER, query)
        _busy.value = true
        scope.launch {
            try {
                val preparation = NeedleSessions.preparePhone(app)
                preparation.message?.let { note ->
                    _messages.value = _messages.value + Message(
                        role = Role.SYSTEM,
                        text = note,
                        error = !preparation.ok,
                    )
                }
                if (!preparation.ok) return@launch

                val spec = if (preparation.lean) NeedleSessions.leanSpec(app) else NeedleSessions.phoneSpec(app)
                val decorated = buildInput(query)
                val reply = NeedleEngine.run(
                    app,
                    spec,
                    decorated,
                    maxSteps = 4,
                    maxNewTokens = NeedlePrefs.maxNewTokens(app),
                ).getOrElse { error ->
                    _messages.value = _messages.value + Message(
                        Role.SYSTEM,
                        error.message ?: "The model could not answer.",
                        error = true,
                    )
                    return@launch
                }

                val answerText = reply.text
                    ?: reply.results.lastOrNull()
                    ?: if (reply.calls.isEmpty()) "I could not turn that into an action." else "Done."

                _messages.value = _messages.value + Message(
                    role = Role.ASSISTANT,
                    text = prettyResults(answerText),
                    reasoning = reply.reasoning,
                    confidence = reply.percentConfidence,
                    tools = reply.calls.map { it.describe() },
                    results = reply.results,
                )
                history.addLast(query to answerText.take(300))
                while (history.size > 4) history.removeFirst()
                NeedleEngine.refresh(app)
            } finally {
                _busy.value = false
            }
        }
    }

    fun addSystem(text: String, error: Boolean = false) {
        _messages.value = _messages.value + Message(Role.SYSTEM, text, error = error)
    }

    fun clear() {
        _messages.value = emptyList()
        history.clear()
    }

    private fun buildInput(query: String): String {
        if (history.isEmpty()) return query
        val context = history.joinToString("\n") { (user, assistant) ->
            "User: $user\nAssistant: ${assistant.take(200)}"
        }
        return "Earlier in this conversation:\n$context\n\nUser: $query"
    }

    /** Tool results arrive as JSON; show readable text when that is what they are. */
    private fun prettyResults(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> summariseJson(trimmed) ?: trimmed
            trimmed.isBlank() -> "(no output)"
            else -> trimmed
        }
    }

    private fun summariseJson(text: String): String? = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).joinToString("\n") { index ->
            array.opt(index)?.toString() ?: "null"
        }
    }.getOrNull() ?: runCatching {
        val entries = mutableListOf<String>()
        val json = org.json.JSONObject(text)
        json.keys().forEach { key ->
            val value = json.opt(key)
            entries += "$key: $value"
        }
        entries.joinToString("\n")
    }.getOrNull()
}
