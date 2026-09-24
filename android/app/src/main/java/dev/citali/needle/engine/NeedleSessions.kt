package dev.citali.needle.engine

import android.content.Context
import dev.citali.needle.BuildConfig
import dev.citali.needle.tools.PhoneTools

/**
 * Builds the sessions the model runs in.
 *
 * Phone mode exposes the device tools the user enabled in Settings. If the
 * declared catalogue does not fit the model's context, the engine reports it and
 * this helper retries with just the essentials, so a large tool set degrades into
 * a smaller one instead of a dead app.
 */
object NeedleSessions {

    data class Preparation(val ok: Boolean, val lean: Boolean, val message: String?)

    fun phoneSystemPrompt(): String = """
        You are Needle, a small assistant running on this Android phone. Answer in one or two short
        sentences. When the request needs the phone to do something, call the matching tool and use
        exactly the values the user gave. If no tool fits, say so plainly. ${NeedleEngine.dateFact()}
    """.trimIndent()

    fun phoneSpec(context: Context): NeedleEngine.SessionSpec {
        val packs = NeedlePrefs.toolPacks(context)
        val tools = PhoneTools.tools(context, packs)
        val key = "phone-" + BuildConfig.NEEDLE_ENGINE_VERSION + "-" + packs.map { it.name }.sorted().joinToString("+")
        return NeedleEngine.SessionSpec(key, phoneSystemPrompt(), tools)
    }

    fun leanSpec(context: Context): NeedleEngine.SessionSpec {
        val tools = PhoneTools.tools(context, setOf(PhoneTools.Pack.CORE))
        return NeedleEngine.SessionSpec("phone-lean-v1", phoneSystemPrompt(), tools)
    }

    /** Prepares the phone session, falling back to the essentials-only set. */
    suspend fun preparePhone(context: Context): Preparation {
        val full = phoneSpec(context)
        NeedleEngine.prepare(context, full).onSuccess {
            return Preparation(ok = true, lean = false, message = null)
        }
        val lean = leanSpec(context)
        return NeedleEngine.prepare(context, lean).fold(
            onSuccess = {
                Preparation(
                    ok = true,
                    lean = true,
                    message = "The full tool list was too large for the model's context, so Needle is " +
                        "using the Essentials tools only. Turn off a tool pack in Settings to fit more " +
                        "of the ones you want.",
                )
            },
            onFailure = { error ->
                Preparation(ok = false, lean = false, message = error.message)
            },
        )
    }
}
