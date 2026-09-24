package dev.citali.needle.tools

/**
 * Hooks for the two device tools that need a visible Activity: the camera capture
 * flow and the biometric prompt. `MainActivity` fills them in while it is on
 * screen and clears them when it stops, so a tool that runs from a background
 * context can say so honestly instead of failing with a platform exception.
 */
object ActivityBridges {

    /** Takes a photo and returns a human-readable result. */
    @Volatile
    var photoCapture: (suspend () -> String)? = null

    /** Shows the fingerprint / face prompt and returns a human-readable result. */
    @Volatile
    var biometricCheck: (suspend (String) -> String)? = null

    val hasCamera: Boolean get() = photoCapture != null

    val hasBiometric: Boolean get() = biometricCheck != null
}
