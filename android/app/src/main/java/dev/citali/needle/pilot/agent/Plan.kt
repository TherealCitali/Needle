package dev.citali.needle.pilot.agent

/**
 * The plan shown to the user before any task runs, plus a parsed [TaskIntent]
 * that the built-in deterministic executor can follow even without an AI key.
 */
data class PlanStep(
    val title: String,
    val detail: String,
    val highRisk: Boolean = false,
)

data class Plan(
    val command: String,
    val steps: List<PlanStep>,
    val intent: TaskIntent,
    /** Ordered goals for a chained command; empty when the task has a single goal. */
    val subGoals: List<String> = emptyList(),
)

sealed class TaskIntent {
    data class OpenApp(val packageName: String?, val label: String) : TaskIntent()
    data class OpenAppAndSearch(val packageName: String?, val label: String, val query: String) : TaskIntent()
    data class MessageDraft(
        val packageName: String,
        val label: String,
        val contact: String?,
        val message: String,
        val send: Boolean,
    ) : TaskIntent()
    data class Generic(val description: String) : TaskIntent()
}

data class AppSpec(val packageName: String?, val label: String)

/** Turns a natural-language command into a plan + structured intent. */
object CommandPlanner {

    private val appKeywords = linkedMapOf(
        "whatsapp" to AppSpec("com.whatsapp", "WhatsApp"),
        "youtube" to AppSpec("com.google.android.youtube", "YouTube"),
        "chrome" to AppSpec("com.android.chrome", "Chrome"),
        "play store" to AppSpec("com.android.vending", "Play Store"),
        "calculator" to AppSpec("com.google.android.calculator", "Calculator"),
        "photos" to AppSpec("com.google.android.apps.photos", "Google Photos"),
        "gallery" to AppSpec(null, "Gallery"),
        "maps" to AppSpec("com.google.android.apps.maps", "Maps"),
        "gmail" to AppSpec("com.google.android.gm", "Gmail"),
        "instagram" to AppSpec("com.instagram.android", "Instagram"),
        "clock" to AppSpec("com.google.android.deskclock", "Clock"),
        "calendar" to AppSpec("com.google.android.calendar", "Calendar"),
        "settings" to AppSpec("com.android.settings", "Settings"),
    )

    /**
     * @param aiAvailable when true, an AI provider is configured and will drive
     * the run. Only commands that map cleanly onto a built-in routine are locked
     * to that routine; everything else stays open-ended so the model can follow
     * the user's actual wording instead of being forced down a keyword path.
     */
    fun parse(
        command: String,
        aiAvailable: Boolean = false,
        resolveApp: (String) -> AppSpec? = { null },
    ): Plan {
        val trimmed = command.trim()
        val normalized = trimmed.lowercase()
        val match = matchApp(normalized)

        // Prefer what is actually installed over the built-in keyword table: the
        // table's package names are guesses that vary by OEM and region, and it
        // cannot know about apps like Brevent at all.
        val installed = match?.let { resolveApp(it.key) }
        if (installed != null && aiAvailable && isSimpleRoutine(trimmed, match.key)) {
            return buildRoutine(trimmed, match.key, installed)
        }

        // With AI available, only take the deterministic route for the simple,
        // unambiguous shapes it handles well. Anything longer or with extra
        // clauses is better served by the model reading the live screen.
        if (aiAvailable && match != null && !isSimpleRoutine(trimmed, match.key)) {
            return aiPlan(trimmed, match.value.label)
        }
        if (aiAvailable && match == null) {
            return aiPlan(trimmed, null)
        }

        val spec = installed ?: match?.value
        return when {
            match != null && match.key == "whatsapp" -> messagePlan(trimmed, spec!!)
            match != null && extractQuery(trimmed) != null -> searchPlan(trimmed, spec!!)
            match != null -> openPlan(trimmed, spec!!)
            "delete" in normalized && ("screenshot" in normalized || "photo" in normalized || "image" in normalized || "gallery" in normalized) ->
                deletePlan(trimmed)
            "battery saver" in normalized -> batteryPlan(trimmed)
            else -> genericPlan(trimmed)
        }
    }

    /** Routes a simple command to the right built-in routine for a known app. */
    private fun buildRoutine(command: String, appKey: String, spec: AppSpec): Plan = when {
        appKey == "whatsapp" -> messagePlan(command, spec)
        extractQuery(command) != null -> searchPlan(command, spec)
        else -> openPlan(command, spec)
    }

    private fun openPlan(command: String, spec: AppSpec): Plan {
        val steps = listOf(
            PlanStep("Open ${spec.label}", "Launch ${spec.label}."),
            PlanStep("Verify", "Confirm the app is in the foreground."),
        )
        return Plan(command, steps, TaskIntent.OpenApp(spec.packageName, spec.label))
    }

    private fun searchPlan(command: String, spec: AppSpec): Plan {
        val query = extractQuery(command).orEmpty()
        val steps = listOf(
            PlanStep("Open ${spec.label}", "Launch ${spec.label}."),
            PlanStep("Find search", "Locate the accessible search field."),
            PlanStep("Enter query", "Type \"$query\" into the search field."),
            PlanStep("Submit", "Submit the search and verify results."),
        )
        return Plan(command, steps, TaskIntent.OpenAppAndSearch(spec.packageName, spec.label, query))
    }

    private fun messagePlan(command: String, spec: AppSpec): Plan {
        val contact = extractContact(command)
        val message = extractMessage(command)
        val send = !(
            command.contains("do not send", ignoreCase = true) ||
                command.contains("don't send", ignoreCase = true) ||
                command.contains("dont send", ignoreCase = true) ||
                command.contains("without sending", ignoreCase = true) ||
                command.contains("as a draft", ignoreCase = true)
            )
        val steps = mutableListOf<PlanStep>()
        steps += PlanStep("Open ${spec.label}", "Launch ${spec.label}.")
        steps += PlanStep("Find ${contact ?: "the contact"}", "Locate the conversation with the named person.")
        steps += PlanStep("Draft the message", "Type the requested text into the message field.")
        steps += if (send) {
            PlanStep("Send the message", "Send after an additional confirmation.", highRisk = true)
        } else {
            PlanStep("Do not send", "Leave the message as a draft; no send action will be attempted.")
        }
        return Plan(
            command,
            steps,
            TaskIntent.MessageDraft(spec.packageName ?: "com.whatsapp", spec.label, contact, message, send)
        )
    }

    private fun deletePlan(command: String): Plan {
        val steps = listOf(
            PlanStep("Open Gallery", "Launch the available Gallery application."),
            PlanStep("Find matches", "Identify items matching the requested scope."),
            PlanStep("Review matches", "Show the matched items and verify the deletion scope."),
            PlanStep("Delete matched items", "Delete only after an additional confirmation.", highRisk = true),
        )
        return Plan(command, steps, TaskIntent.Generic("gallery-delete"))
    }

    private fun batteryPlan(command: String): Plan {
        val steps = listOf(
            PlanStep("Open Settings", "Launch Android Settings."),
            PlanStep("Find Battery Saver", "Locate the Battery Saver setting."),
            PlanStep("Enable Battery Saver", "Change the setting after the approved plan."),
        )
        return Plan(command, steps, TaskIntent.Generic("battery-saver"))
    }

    /**
     * Matches an app keyword on word boundaries.
     *
     * A bare `contains` matched any app name mentioned anywhere in the sentence,
     * so "open brevent and enable Instagram lite from there" matched "instagram"
     * and became "Open Instagram" -- dropping both the real target app and the
     * actual goal. Preference is given to the app the user asked to open/launch.
     */
    private fun matchApp(normalized: String): Map.Entry<String, AppSpec>? {
        fun mentioned(key: String): Boolean =
            Regex("\\b" + Regex.escape(key) + "\\b").containsMatchIn(normalized)

        val mentions = appKeywords.entries.filter { mentioned(it.key) }
        if (mentions.isEmpty()) return null
        // If several apps are named, the command spans more than one app and is
        // not a single built-in routine; let the caller fall through to AI.
        if (mentions.size > 1) return null

        val entry = mentions.first()
        // Only treat it as "the app to open" when it directly follows an open verb.
        val opened = Regex(
            "\\b(?:open|launch|start|go to|goto)\\s+(?:the\\s+)?" + Regex.escape(entry.key) + "\\b"
        ).containsMatchIn(normalized)
        val leadsCommand = normalized.startsWith(entry.key)
        val onlySubject = Regex("\\b(?:in|on|from|using|with)\\s+" + Regex.escape(entry.key) + "\\b")
            .containsMatchIn(normalized)
        return if (opened || leadsCommand || onlySubject) entry else null
    }

    /**
     * True for short commands that are exactly "open X" or "open X and search Y"
     * with no extra instructions layered on top.
     */
    private fun isSimpleRoutine(command: String, appKey: String): Boolean {
        val n = command.lowercase().trim().trimEnd('.', '!')
        if (n.length > 90) return false
        // Extra clauses mean the user wants something the fixed routine cannot do.
        val extraClauses = listOf(
            " then ", " after ", " and then ", " scroll", " play ", " open the first",
            " first result", " click ", " tap ", " subscribe", " like ", " comment",
            " download", " share", " delete", " reply", " select ",
        )
        if (extraClauses.any { it in n }) return false
        if (hasMultipleSteps(n)) return false
        if (appKey == "whatsapp") return true
        val hasQuery = extractQuery(command) != null
        val verbs = Regex("""\b(open|launch|start|go to|search|find|look up|play|watch)\b""")
        val verbCount = verbs.findAll(n).count()
        return if (hasQuery) verbCount <= 2 else verbCount <= 1
    }

    /**
     * Detects a command that chains more than one goal, e.g.
     * "open X and enable Y from there". These need the model to work through the
     * screens in sequence; a fixed routine would silently do only the first part.
     */
    private fun hasMultipleSteps(normalized: String): Boolean {
        val connectors = Regex(
            """\b(?:and then|then|after that|afterwards|next,|;|,\s*then)\b"""
        )
        if (connectors.containsMatchIn(normalized)) return true

        // "and <verb>" chains a second instruction ("open X and enable Y"),
        // unlike "and" merely joining nouns ("open maps and search cafes" is
        // handled as a single search routine by the query extractor).
        val andVerb = Regex(
            """\band\s+(?:also\s+)?(?:enable|disable|turn|switch|set|toggle|allow|block|""" +
                """install|uninstall|update|clear|remove|delete|add|create|send|share|""" +
                """download|copy|paste|change|select|choose|pick|tap|click|press|scroll|""" +
                """navigate|go|open|launch)\b"""
        )
        if (andVerb.containsMatchIn(normalized)) return true

        // A reference back to a previous screen implies a second stage.
        if (Regex("""\bfrom (?:there|here|it|that)\b""").containsMatchIn(normalized)) return true
        return false
    }

    /** Open-ended plan: the model decides each step from the live screen. */
    private fun aiPlan(command: String, appLabel: String?): Plan {
        val goals = splitGoals(command)
        val steps = if (goals.size > 1) {
            // Show the user each stage that was detected, so a chained command is
            // visibly a chain before they approve it.
            goals.mapIndexed { i, g ->
                PlanStep("Step ${i + 1}: ${g.replaceFirstChar { c -> c.uppercase() }}", "Work through this stage on screen.")
            } + PlanStep("Verify and finish", "Confirm every stage is done, or ask before anything risky.")
        } else {
            val destination = appLabel?.let { "Open $it" } ?: "Identify the destination"
            listOf(
                PlanStep(destination, appLabel?.let { "Launch $it." } ?: "Work out which app or screen the request needs."),
                PlanStep("Read the screen", "Build a redacted snapshot of what is on screen."),
                PlanStep("Work through the task", "Decide and take one validated action at a time."),
                PlanStep("Verify and finish", "Confirm the outcome, or ask before anything risky."),
            )
        }
        return Plan(command, steps, TaskIntent.Generic(command), if (goals.size > 1) goals else emptyList())
    }

    /**
     * Splits a chained command into ordered goals so the model gets an explicit
     * checklist instead of one long sentence it can declare done too early.
     */
    fun splitGoals(command: String): List<String> {
        val text = command.trim().trimEnd('.', '!')
        val separator = Regex(
            """\s*(?:,\s*)?\b(?:and then|then|after that|afterwards)\b\s*|""" +
                """\s*;\s*|""" +
                """\s+and\s+(?=(?:also\s+)?(?:enable|disable|turn|switch|set|toggle|allow|block|""" +
                """install|uninstall|update|clear|remove|delete|add|create|send|share|download|copy|""" +
                """paste|change|select|choose|pick|tap|click|press|scroll|navigate|go|open|launch)\b)""",
            RegexOption.IGNORE_CASE,
        )
        val parts = text.split(separator).map { it.trim().trim(',') }.filter { it.length > 2 }
        return if (parts.size > 1) parts else emptyList()
    }

    private fun genericPlan(command: String): Plan {
        val steps = listOf(
            PlanStep("Identify the destination", "Determine which app or Android surface matches the request."),
            PlanStep("Observe the current UI", "Build a redacted accessibility-tree snapshot."),
            PlanStep("Complete the requested task", "Continue one validated action at a time."),
            PlanStep("Verify completion", "Confirm the outcome or ask a follow-up question."),
        )
        return Plan(command, steps, TaskIntent.Generic(command))
    }

    // ---- Extraction helpers ----------------------------------------------

    private fun extractQuery(command: String): String? {
        Regex("""["“]([^"”]{1,120})["”]""").find(command)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val patterns = listOf(
            Regex("""search(?: the (?:web|net))? for (.{1,120}?)(?:[.,!]| and | on \w+)?$""", RegexOption.IGNORE_CASE),
            Regex("""search (.{1,120}?)(?:[.,!]| and | on \w+)?$""", RegexOption.IGNORE_CASE),
            Regex("""(?:play|watch|look up|look for|find) (.{1,120}?)(?:[.,!]| and | on \w+)?$""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(command.trim())
            val value = match?.groupValues?.get(1)?.trim()
            if (!value.isNullOrBlank() && value.length >= 2) return value
        }
        return null
    }

    private fun extractContact(command: String): String? {
        val patterns = listOf(
            Regex("""(?:message|text|msg|whatsapp)\s+(?:to\s+)?([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""),
            Regex("""\bto\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""),
        )
        val stopWords = setOf("saying", "that", "who", "with", "without", "and", "the", "but", "do", "dont", "don't")
        for (pattern in patterns) {
            val value = pattern.find(command)?.groupValues?.get(1)?.trim()
            if (!value.isNullOrBlank() && value.lowercase() !in stopWords) return value
        }
        return null
    }

    private fun extractMessage(command: String): String {
        Regex("""["“]([^"”]{1,400})["”]""").find(command)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val marker = Regex("""\b(saying|that says|reading|with the message|with text|:\s+)""", RegexOption.IGNORE_CASE)
            .find(command)?.range?.last
        if (marker != null) {
            val rest = command.substring(marker + 1).trim().removePrefix(":").trim()
            val but = Regex("""\s+but\s+""", RegexOption.IGNORE_CASE).find(rest)?.range?.first
            val message = if (but != null) rest.substring(0, but) else rest
            if (message.isNotBlank()) return message.trim()
        }
        return ""
    }
}
