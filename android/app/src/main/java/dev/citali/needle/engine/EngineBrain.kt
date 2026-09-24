package dev.citali.needle.engine

import android.content.Context
import dev.citali.needle.BuildConfig
import dev.citali.needle.pilot.accessibility.UiSnapshot
import dev.citali.needle.pilot.agent.Plan
import dev.citali.needle.pilot.data.AppInventory
import org.json.JSONObject

/**
 * TaskPilot's control loop, driven by the on-device Needle model instead of a
 * remote provider.
 *
 * The automation actions are declared to Needle as tools, so its decode grammar
 * constrains every reply to a valid call: the model physically cannot answer with
 * prose where an action is required. The call is then converted into the same
 * JSON action contract the remote provider path uses, which means the safety
 * policy, validation and executor in `pilot/agent` are unchanged and still the
 * only way anything is performed on screen.
 */
object EngineBrain {

    private const val SESSION_KEY = "needle-automation-v1"
    private const val MAX_TREE_CHARS = 3000
    private const val MAX_APP_LINES = 25

    val systemPrompt: String = """
        You operate an Android phone for its owner. Read the CURRENT SCREEN and call exactly one
        tool for the single next step of the TASK, then stop. Prefer #ids that appear on the
        current screen. Never type passwords, OTPs, PINs, card numbers or security codes: call
        ask_user instead. Use only package names from INSTALLED APPS, never invent one. Call
        task_complete only when every part of the task is finished, and task_failed when it
        cannot be done.
    """.trimIndent()

    private fun placeholder(): String = "noted"

    val tools: List<NeedleTool> = listOf(
        NeedleTool(
            "tap",
            "Tap one control on the current screen.",
            listOf(
                NeedleParam("target", description = "#id from the current screen, or text or resource-id of the control."),
                NeedleParam("description", description = "Short reason.", required = false),
            ),
        ) { placeholder() },

        NeedleTool(
            "long_press",
            "Press and hold one control.",
            listOf(
                NeedleParam("target", description = "#id or text of the control."),
                NeedleParam("description", description = "Short reason.", required = false),
            ),
        ) { placeholder() },

        NeedleTool(
            "type_text",
            "Type text into a text field on the current screen.",
            listOf(
                NeedleParam("target", description = "#id of an editable field."),
                NeedleParam("text", description = "Exact text to enter."),
            ),
        ) { placeholder() },

        NeedleTool(
            "swipe",
            "Scroll the screen.",
            listOf(
                NeedleParam(
                    name = "direction",
                    description = "Direction to scroll.",
                    enumValues = listOf("up", "down", "left", "right"),
                )
            ),
        ) { placeholder() },

        NeedleTool(
            "press_key",
            "Press an Android system key.",
            listOf(
                NeedleParam(
                    name = "code",
                    description = "Which key to press.",
                    enumValues = listOf("back", "home", "enter", "recents"),
                )
            ),
        ) { placeholder() },

        NeedleTool(
            "open_app",
            "Launch an installed app by its package name.",
            listOf(NeedleParam("package", description = "Exact package name from INSTALLED APPS.")),
        ) { placeholder() },

        NeedleTool(
            "wait",
            "Wait for the screen to finish loading before the next step.",
            listOf(NeedleParam("millis", type = "integer", description = "How long to wait, 200 to 5000.")),
        ) { placeholder() },

        NeedleTool(
            "ask_user",
            "Stop and ask the owner a question when the screen is ambiguous or sensitive input is needed.",
            listOf(NeedleParam("question", description = "The question to ask.")),
        ) { placeholder() },

        NeedleTool(
            "task_complete",
            "Finish the task because every part of it is done.",
            listOf(NeedleParam("summary", description = "What was accomplished.")),
        ) { placeholder() },

        NeedleTool(
            "task_failed",
            "Give up on the task with an explanation.",
            listOf(NeedleParam("reason", description = "Why the task cannot be completed.")),
        ) { placeholder() },
    )

    private val spec = NeedleEngine.SessionSpec(SESSION_KEY, systemPrompt, tools)

    fun engineVersion(): String = BuildConfig.NEEDLE_ENGINE_VERSION

    /** True when the model is loaded with the automation tool set. */
    fun isReady(): Boolean = NeedleEngine.isReady()

    val toolNames: List<String> = tools.map { it.name }

    /**
     * One decision: observe the current screen, ask the model for the single next
     * action, and return it as the JSON contract [dev.citali.needle.pilot.agent.LlmClient.parseAction]
     * already understands.
     */
    suspend fun proposeAction(
        context: Context,
        plan: Plan,
        snapshot: UiSnapshot,
        recent: List<String>,
    ): Result<String> {
        val input = buildPrompt(context, plan, snapshot, recent)
        val envelope = NeedleEngine.completeEnvelope(context, spec, input, maxNewTokens = 320)
            .getOrElse { error -> return Result.failure(error) }
        val parsed = NeedleEngine.parseEnvelope(envelope)
        val call = parsed.calls.firstOrNull()
        if (call != null) {
            val action = call.toActionJson()
            if (action != null) return Result.success(action.toString())
            return Result.failure(IllegalStateException("the model called an unknown tool: ${call.name}"))
        }
        parsed.text?.takeIf { it.isNotBlank() }?.let { text ->
            // The model spoke instead of acting. Turning that into a question keeps
            // the loop honest: it asks the user rather than guessing at a tap.
            return Result.success(
                JSONObject()
                    .put("action", "ask")
                    .put("question", text.take(240))
                    .toString()
            )
        }
        return Result.failure(IllegalStateException("the model did not choose an action"))
    }

    private fun NeedleEngine.ToolCall.toActionJson(): JSONObject? {
        fun arg(key: String): String = arguments.optString(key).trim()
        return when (name) {
            "tap" -> JSONObject()
                .put("action", "tap")
                .put("target", arg("target"))
                .put("description", arg("description"))
                .takeIf { arg("target").isNotBlank() }

            "long_press" -> JSONObject()
                .put("action", "long_press")
                .put("target", arg("target"))
                .put("description", arg("description"))
                .takeIf { arg("target").isNotBlank() }

            "type_text" -> JSONObject()
                .put("action", "type")
                .put("target", arg("target"))
                .put("text", arg("text"))
                .takeIf { arg("target").isNotBlank() && arg("text").isNotBlank() }

            "swipe" -> JSONObject()
                .put("action", "swipe")
                .put("direction", arg("direction").ifBlank { "up" })

            "press_key" -> JSONObject()
                .put("action", "key")
                .put("code", arg("code").ifBlank { "back" })

            "open_app" -> JSONObject()
                .put("action", "open_app")
                .put("package", arg("package"))
                .takeIf { arg("package").isNotBlank() }

            "wait" -> JSONObject()
                .put("action", "wait")
                .put("millis", arguments.optInt("millis", 800).coerceIn(200, 5000))

            "ask_user" -> JSONObject()
                .put("action", "ask")
                .put("question", arg("question").ifBlank { "I need more detail to continue." })

            "task_complete" -> JSONObject()
                .put("action", "complete")
                .put("summary", arg("summary").ifBlank { "Task completed." })

            "task_failed" -> JSONObject()
                .put("action", "fail")
                .put("reason", arg("reason").ifBlank { "The task could not be completed." })

            else -> null
        }
    }

    private fun buildPrompt(
        context: Context,
        plan: Plan,
        snapshot: UiSnapshot,
        recent: List<String>,
    ): String {
        val builder = StringBuilder()
        builder.append("TASK: ").append(plan.command).append('\n')
        if (plan.subGoals.isNotEmpty()) {
            builder.append("REMAINING:\n")
            plan.subGoals.forEachIndexed { index, goal -> builder.append(index + 1).append(". ").append(goal).append('\n') }
        }
        builder.append("RECENT ACTIONS: ")
        builder.append(if (recent.isEmpty()) "(none yet)" else recent.takeLast(8).joinToString(" | "))
        builder.append('\n')
        builder.append("INSTALLED APPS:\n")
        builder.append(trimLines(AppInventory.promptListing(context, limit = MAX_APP_LINES), MAX_APP_LINES))
        builder.append('\n')
        builder.append("CURRENT SCREEN:\n")
        builder.append(trimChars(snapshot.toPromptString(), MAX_TREE_CHARS))
        builder.append('\n')
        builder.append("Reply with exactly one tool call.")
        return builder.toString()
    }

    private fun trimChars(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.take(limit)
        val lastNewline = cut.lastIndexOf('\n')
        return (if (lastNewline > limit / 2) cut.substring(0, lastNewline) else cut) + "\n… (trimmed)"
    }

    private fun trimLines(text: String, limit: Int): String {
        val lines = text.lines()
        return if (lines.size <= limit) text else lines.take(limit).joinToString("\n") + "\n…"
    }

    /** Only used by the Tools tab to show what the automation brain can do. */
    fun describeTools(): List<String> = tools.map { "${it.name}: ${it.description}" }
}
