package dev.citali.needle.engine

import androidx.annotation.Keep

/**
 * JNI surface of the Needle engine (Cactus Compute).
 *
 * Every method here is backed by `libneedlejni.so`, which links the published
 * Needle C engine for the device ABI. The engine keeps one process-global,
 * non-thread-safe model, so callers must serialise on [NeedleEngine.dispatcher].
 */
@Keep
object NeedleNative {

    init {
        System.loadLibrary("needlejni")
    }

    /** True when this build really links the engine (false for the stub build). */
    external fun nativeEngineAvailable(): Boolean

    /** Memory-maps a `.cact` archive and loads it. Returns 0 on success. */
    external fun nativeLoadModel(path: String): Int

    /** Builds the static prefix from a system prompt plus tool schemas. Returns the prefix token count, or a negative code. */
    external fun nativeInit(system: String, toolsJson: String): Int

    /** Runs one completion. Returns the raw JSON envelope, or null on failure. */
    external fun nativeComplete(input: String, maxNewTokens: Int): String?

    /** The engine's own last error message, if any. */
    external fun nativeLastError(): String

    /** Clears the conversation state kept inside the engine. */
    external fun nativeReset()

    /** Releases the mapping and buffers. */
    external fun nativeUnload()
}
