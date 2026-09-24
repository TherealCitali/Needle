package dev.citali.needle.remote

import android.content.Context
import android.util.Log
import dev.citali.needle.engine.NeedleEngine
import dev.citali.needle.engine.NeedlePrefs
import dev.citali.needle.engine.NeedleSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Remote control over Telegram, with no third-party library.
 *
 * The original Python app used `pyTelegramBotAPI`; a long-poll against the Bot
 * API's `getUpdates` does the same job in a few dozen lines and keeps the APK
 * free of another dependency. Texting the bot runs the same on-device Needle
 * session as the chat screen, so the phone answers even when it is in a pocket.
 */
object TelegramBridge {

    private const val TAG = "NeedleTelegram"
    private const val API = "https://api.telegram.org/bot"

    data class State(
        val running: Boolean = false,
        val handled: Int = 0,
        val lastMessage: String? = null,
        val lastReply: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var offset = 0L

    fun start(context: Context, token: String) {
        stop()
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            _state.value = State(error = "Add a bot token first (talk to @BotFather on Telegram).")
            return
        }
        _state.value = State(running = true)
        val app = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch { pollLoop(app, cleanToken) }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope = null
        _state.value = _state.value.copy(running = false)
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun pollLoop(context: Context, token: String) {
        while (scope?.isActive == true) {
            try {
                val updates = getUpdates(token)
                for (update in updates) {
                    val updateId = update.optLong("update_id", -1L)
                    if (updateId >= 0) offset = updateId + 1
                    val message = update.optJSONObject("message") ?: continue
                    val chatId = message.optJSONObject("chat")?.optLong("id") ?: continue
                    val text = message.optString("text").trim()
                    if (text.isEmpty()) continue
                    _state.value = _state.value.copy(lastMessage = text)
                    val reply = handle(context, token, text)
                    sendMessage(token, chatId, reply)
                    _state.value = _state.value.copy(
                        handled = _state.value.handled + 1,
                        lastReply = reply.take(160),
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "poll failed: ${error.message}")
                _state.value = _state.value.copy(error = error.message ?: "Telegram polling failed.")
                delay(4_000)
            }
        }
    }

    private suspend fun handle(context: Context, token: String, text: String): String {
        if (text == "/start" || text == "/help") {
            return "Needle is listening. Text me a command such as \"turn on the flashlight\", " +
                "\"what is my battery level?\" or \"send sms to +91... saying hello\"."
        }
        if (text == "/stop") {
            NeedlePrefs.setTelegramEnabled(context, false)
            stop()
            return "Remote control turned off."
        }
        val preparation = NeedleSessions.preparePhone(context)
        if (!preparation.ok) {
            return "The on-device model is not ready yet: ${preparation.message ?: "open the app to check Settings."}"
        }
        val spec = if (preparation.lean) NeedleSessions.leanSpec(context) else NeedleSessions.phoneSpec(context)
        val reply = NeedleEngine.run(context, spec, text, maxSteps = 4, maxNewTokens = NeedlePrefs.maxNewTokens(context))
            .getOrElse { error -> return "I could not run that: ${error.message}" }

        return buildString {
            append(reply.text?.take(1_500) ?: "Done.")
            if (reply.results.isNotEmpty()) {
                append("\n\n")
                reply.results.forEach { append("• ").append(it.take(400)).append('\n') }
            }
            reply.reasoning?.takeIf { it.isNotBlank() }?.let { append("\nReasoning: ").append(it.take(300)) }
            reply.percentConfidence?.let { append("\nConfidence: ").append(it).append('%') }
        }.trim()
    }

    private fun getUpdates(token: String): List<JSONObject> {
        val url = URL("$API$token/getUpdates?timeout=25&limit=10&offset=$offset")
        val body = request(url, null)
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("description", "Telegram rejected the bot token."))
        }
        val result = json.optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).mapNotNull { result.optJSONObject(it) }
    }

    private fun sendMessage(token: String, chatId: Long, text: String) {
        val url = URL("$API$token/sendMessage")
        val payload = "chat_id=$chatId&text=" + URLEncoder.encode(text.take(3_900), "UTF-8")
        runCatching { request(url, payload) }
            .onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    private fun request(url: URL, body: String?): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 20_000
            readTimeout = 40_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
            text
        } finally {
            connection.disconnect()
        }
    }
}
