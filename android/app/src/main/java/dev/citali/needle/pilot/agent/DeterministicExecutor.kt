package dev.citali.needle.pilot.agent

import dev.citali.needle.pilot.accessibility.UiSnapshot

/**
 * Built-in executor that follows a structured [TaskIntent] without an AI key.
 *
 * It works one action at a time and prefers to pause with an [Action.AskUser]
 * rather than guess when the expected control cannot be confidently identified.
 */
class DeterministicExecutor(private val intent: TaskIntent) {

    private var openedOnce = false
    private var openMisses = 0
    private var typed = false
    private var submitted = false
    private var submitRetries = 0
    private var searchOpened = false
    private var contactTyped = false
    private var conversationOpened = false
    private var messageTyped = false
    private var sent = false
    private var askedOnce = false
    private var askedNullOpen = false
    private var userAnswer: String? = null

    fun onUserAnswer(text: String) {
        userAnswer = text
    }

    fun next(snapshot: UiSnapshot): Action = when (intent) {
        is TaskIntent.OpenApp -> openApp(snapshot, intent)
        is TaskIntent.OpenAppAndSearch -> search(snapshot, intent)
        is TaskIntent.MessageDraft -> message(snapshot, intent)
        is TaskIntent.Generic -> generic(snapshot, intent)
    }

    private fun ensureOpened(snapshot: UiSnapshot, packageName: String?, label: String): Action? {
        if (packageName == null) {
            // OEM-dependent app (e.g. Gallery). We cannot launch it deterministically,
            // so we ask once and then treat it as opened on the user's confirmation.
            if (!askedNullOpen) {
                askedNullOpen = true
                return Action.AskUser("Open $label yourself, then approve to continue.")
            }
            return null
        }
        if (snapshot.packageName == packageName) {
            openMisses = 0
            return null
        }
        if (!openedOnce) {
            openedOnce = true
            return Action.OpenApp(packageName, label)
        }
        openMisses++
        if (openMisses > 4) return Action.Complete("$label did not come to the foreground; stopping to avoid loops.")
        return Action.Wait(900)
    }

    private fun openApp(snapshot: UiSnapshot, intent: TaskIntent.OpenApp): Action {
        ensureOpened(snapshot, intent.packageName, intent.label)?.let { return it }
        return Action.Complete("${intent.label} is open.")
    }

    private fun search(snapshot: UiSnapshot, intent: TaskIntent.OpenAppAndSearch): Action {
        ensureOpened(snapshot, intent.packageName, intent.label)?.let { return it }

        val editable = snapshot.editableNodes().firstOrNull()
        if (editable == null) {
            // No text field yet. If we have already typed, the field closing is
            // the normal sign that the search was submitted and results rendered.
            if (typed && submitted) {
                return Action.Complete("Searched ${intent.label} for \"${intent.query}\".")
            }
            val searchControl = snapshot.firstByTextOrDescContaining("search", clickableOnly = true)
            return searchControl?.let { Action.Tap("#${it.id}", "Open the search field") }
                ?: Action.AskUser("I can't find the search field in ${intent.label}. What should I do?")
        }
        if (!typed) {
            typed = true
            return Action.Type("#${editable.id}", intent.query)
        }
        if (!submitted) {
            submitted = true
            return Action.Key(Action.KeyAction.ENTER)
        }

        // Submitted, but a text field is still on screen. Verify the query was
        // actually committed instead of assuming success: an unsubmitted search
        // box still holds the query and the results list never appears.
        if (searchStillPending(snapshot, intent.query) && submitRetries < 2) {
            submitRetries++
            return Action.Key(Action.KeyAction.ENTER)
        }
        if (searchStillPending(snapshot, intent.query)) {
            return Action.AskUser(
                "I typed \"${intent.query}\" into ${intent.label} but could not submit it. " +
                    "Press search yourself, then approve to continue."
            )
        }
        return Action.Complete("Searched ${intent.label} for \"${intent.query}\".")
    }

    /**
     * True when the query still looks like it is sitting unsubmitted in the
     * search box: the field retains the exact query and nothing that resembles a
     * results list has appeared.
     */
    private fun searchStillPending(snapshot: UiSnapshot, query: String): Boolean {
        if (query.isBlank()) return false
        val fieldHoldsQuery = snapshot.editableNodes().any {
            it.text?.trim().equals(query.trim(), ignoreCase = true)
        }
        if (!fieldHoldsQuery) return false
        val hasResults = snapshot.root?.flatten()?.any { it.scrollable } == true
        return !hasResults
    }

    private fun message(snapshot: UiSnapshot, intent: TaskIntent.MessageDraft): Action {
        ensureOpened(snapshot, intent.packageName, intent.label)?.let { return it }

        // 1. Open the in-app search.
        if (!searchOpened) {
            val searchControl = snapshot.firstByTextOrDescContaining("search", clickableOnly = true)
                ?: return Action.AskUser("I can't find the search button in ${intent.label}. What should I do?")
            searchOpened = true
            return Action.Tap("#${searchControl.id}", "Open contact search")
        }

        // 2. Type the contact name.
        if (!contactTyped) {
            val contact = intent.contact ?: return Action.AskUser("Which contact should I open?")
            val editable = snapshot.editableNodes().firstOrNull()
                ?: return Action.AskUser("I can't find the contact search field. What should I do?")
            contactTyped = true
            return Action.Type("#${editable.id}", contact)
        }

        // 3. Open the conversation.
        if (!conversationOpened) {
            val contact = intent.contact
            val row = snapshot.firstByTextOrDescContaining(contact ?: "", clickableOnly = true)
                ?: snapshot.clickableNodes().firstOrNull { it.id != snapshot.editableNodes().firstOrNull()?.id }
                ?: return Action.AskUser("I can't confidently identify the conversation. What should I do?")
            conversationOpened = true
            return Action.Tap("#${row.id}", "Open conversation")
        }

        // 4. Type the message.
        if (!messageTyped) {
            val message = intent.message.ifBlank { userAnswer ?: "" }
            if (message.isBlank()) return Action.AskUser("What message should I draft?")
            val field = snapshot.editableNodes().firstOrNull()
                ?: return Action.AskUser("I can't find the message field. What should I do?")
            messageTyped = true
            return Action.Type("#${field.id}", message)
        }

        // 5. Send (optional, high-risk) or finish as draft.
        if (intent.send && !sent) {
            val sendControl = snapshot.firstByTextOrDescContaining("send", clickableOnly = true)
                ?: return Action.AskUser("I can't find the send button. Send it manually?")
            sent = true
            return Action.Tap("#${sendControl.id}", "Send the message")
        }
        return Action.Complete(
            if (intent.send) "Message sent to ${intent.contact ?: "the contact"}."
            else "Draft composed for ${intent.contact ?: "the contact"} and left unsent."
        )
    }

    private fun generic(snapshot: UiSnapshot, intent: TaskIntent.Generic): Action {
        if (!askedOnce) {
            askedOnce = true
            return Action.AskUser(
                "I don't have a built-in routine for: \"${intent.description.take(80)}\". " +
                    "Add an AI provider in Settings to handle open-ended tasks, or give a more specific command."
            )
        }
        return Action.Fail("Open-ended task without a built-in routine or configured AI provider.")
    }
}
