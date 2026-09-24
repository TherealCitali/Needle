package dev.citali.needle.pilot.agent

/**
 * Deterministic safety layer. Every action is classified before execution;
 * the agent never runs unvalidated model output.
 */
enum class RiskLevel { SAFE, CAUTIOUS, HIGH_RISK, BLOCKED }

data class SafetyDecision(val level: RiskLevel, val reason: String)

object SafetyPolicy {

    private val dangerousControlKeywords = listOf(
        "send", "submit", "post", "buy", "purchase", "pay", "checkout",
        "confirm payment", "delete", "remove", "erase", "wipe", "clear data",
        "factory reset", "uninstall", "grant", "allow", "approve", "authorize",
        "transfer", "withdraw", "sign out", "log out", "install now", "enable install",
    )

    private val sensitiveFieldKeywords = listOf(
        "password", "passcode", "pin", "otp", "one-time", "verification code",
        "cvv", "cvc", "card number", "credit", "debit", "mpin", "upi pin",
        "aadhaar", "ssn", "pan card", "recovery code", "security code", "token",
    )

    private val sensitiveValuePatterns = listOf(
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""\b(?:\d[ -]?){13,19}\b"""),
        Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b"""),
        Regex("""\b\d{6,8}\b"""),
        Regex("""\b\d{3}[-.]?\d{3}[-.]?\d{4}\b"""),
    )

    /**
     * Classifies an action. [targetDescription] is the resolved node description
     * (text / content-description / resource-id) when the action is node-directed.
     */
    fun assess(action: Action, targetDescription: String? = null): SafetyDecision = when (action) {
        is Action.Type -> {
            val hint = targetDescription ?: ""
            when {
                isSensitiveField(hint) ->
                    SafetyDecision(RiskLevel.BLOCKED, "Refusing to type into a sensitive field.")
                containsSensitiveValue(action.text) ->
                    SafetyDecision(RiskLevel.BLOCKED, "Refusing to auto-type sensitive data.")
                else ->
                    SafetyDecision(RiskLevel.SAFE, "Typing ordinary text.")
            }
        }
        is Action.Tap, is Action.LongPress -> {
            val desc = targetDescription ?: action.nodeTarget ?: ""
            if (dangerousControlKeywords.any { desc.contains(it, ignoreCase = true) }) {
                SafetyDecision(RiskLevel.HIGH_RISK, "This control can send, delete, pay, or change a security-sensitive setting.")
            } else {
                SafetyDecision(RiskLevel.SAFE, "Tapping a UI control.")
            }
        }
        is Action.OpenApp ->
            SafetyDecision(RiskLevel.SAFE, "Launching an app.")
        is Action.Key ->
            SafetyDecision(RiskLevel.SAFE, "Sending a system key event.")
        is Action.Swipe ->
            SafetyDecision(RiskLevel.SAFE, "Scrolling the screen.")
        is Action.Wait, is Action.AskUser, is Action.Complete, is Action.Fail ->
            SafetyDecision(RiskLevel.SAFE, "No physical effect.")
    }

    fun isSensitiveField(label: String): Boolean =
        sensitiveFieldKeywords.any { label.contains(it, ignoreCase = true) }

    fun containsSensitiveValue(text: String): Boolean =
        sensitiveValuePatterns.any { it.containsMatchIn(text) }
}
