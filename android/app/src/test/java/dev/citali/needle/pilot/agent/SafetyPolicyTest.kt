package dev.citali.needle.pilot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety layer is the only thing standing between a model guess and the screen. */
class SafetyPolicyTest {

    @Test
    fun typingIntoASensitiveFieldIsBlocked() {
        val decision = SafetyPolicy.assess(
            Action.Type("#password", "hunter2"),
            targetDescription = "Password",
        )
        assertEquals(RiskLevel.BLOCKED, decision.level)
    }

    @Test
    fun typingSensitiveValuesIsBlockedAnywhere() {
        val decision = SafetyPolicy.assess(Action.Type("#note", "4111 1111 1111 1111"))
        assertEquals(RiskLevel.BLOCKED, decision.level)
    }

    @Test
    fun controlsThatSendOrPayAreHighRisk() {
        val decision = SafetyPolicy.assess(Action.Tap("#submit"), targetDescription = "Pay now")
        assertEquals(RiskLevel.HIGH_RISK, decision.level)
    }

    @Test
    fun uninstallAndDeleteControlsAreHighRisk() {
        assertEquals(
            RiskLevel.HIGH_RISK,
            SafetyPolicy.assess(Action.LongPress("#row"), targetDescription = "Uninstall app").level,
        )
    }

    @Test
    fun ordinaryInteractionStaysSafe() {
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assess(Action.Tap("#settings"), targetDescription = "Settings").level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assess(Action.Key(Action.KeyAction.BACK)).level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assess(Action.Swipe(Action.SwipeDirection.UP)).level)
        assertEquals(RiskLevel.SAFE, SafetyPolicy.assess(Action.Wait(500)).level)
    }

    @Test
    fun plainTextIsNotMistakenForSecretData() {
        assertFalse(SafetyPolicy.containsSensitiveValue("please order two coffees"))
        assertFalse(SafetyPolicy.isSensitiveField("Search"))
        assertTrue(SafetyPolicy.isSensitiveField("Enter OTP"))
    }
}
