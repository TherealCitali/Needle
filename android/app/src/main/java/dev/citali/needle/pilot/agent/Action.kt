package dev.citali.needle.pilot.agent

/**
 * One validated action. The agent produces at most one of these per cycle and
 * every instance must pass [SafetyPolicy] before [dev.citali.needle.pilot.accessibility.NeedleAccessibilityService]
 * executes it.
 */
sealed class Action {

    /** Tap a node. `target` is "#<id>", a resource-id segment, or a text/description substring. */
    data class Tap(val target: String, val description: String = "") : Action()

    data class LongPress(val target: String, val description: String = "") : Action()

    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    data class Swipe(val direction: SwipeDirection) : Action()

    data class Type(val target: String, val text: String) : Action()

    enum class KeyAction { BACK, HOME, ENTER, RECENTS }

    data class Key(val key: KeyAction) : Action()

    data class OpenApp(val packageName: String, val label: String = "") : Action()

    data class Wait(val millis: Long) : Action()

    data class AskUser(val question: String) : Action()

    data class Complete(val summary: String) : Action()

    data class Fail(val reason: String) : Action()

    /** Target of the action if it is node-directed, used by the safety policy. */
    val nodeTarget: String?
        get() = when (this) {
            is Tap -> target
            is LongPress -> target
            is Type -> target
            else -> null
        }
}
