package dev.citali.needle.engine

import android.content.Context
import dev.citali.needle.tools.PhoneTools

/** Small, non-secret preferences for the on-device assistant. */
object NeedlePrefs {

    private const val FILE = "needle_prefs"
    private const val KEY_PACKS = "tool_packs"
    private const val KEY_TELEGRAM_TOKEN = "telegram_token"
    private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
    private const val KEY_MAX_TOKENS = "max_new_tokens"
    private const val KEY_SHOW_REASONING = "show_reasoning"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun toolPacks(context: Context): Set<PhoneTools.Pack> {
        val stored = prefs(context).getStringSet(KEY_PACKS, null) ?: return PhoneTools.defaultPacks
        val packs = stored.mapNotNull { name -> PhoneTools.Pack.entries.firstOrNull { it.name == name } }.toSet()
        return packs.ifEmpty { setOf(PhoneTools.Pack.CORE) }
    }

    fun setToolPacks(context: Context, packs: Set<PhoneTools.Pack>) {
        prefs(context).edit().putStringSet(KEY_PACKS, packs.map { it.name }.toSet()).apply()
    }

    fun telegramToken(context: Context): String = prefs(context).getString(KEY_TELEGRAM_TOKEN, "").orEmpty()

    fun setTelegramToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TELEGRAM_TOKEN, token.trim()).apply()
    }

    fun telegramEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TELEGRAM_ENABLED, false)

    fun setTelegramEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TELEGRAM_ENABLED, enabled).apply()
    }

    fun maxNewTokens(context: Context): Int = prefs(context).getInt(KEY_MAX_TOKENS, 512).coerceIn(128, 1024)

    fun setMaxNewTokens(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_TOKENS, value.coerceIn(128, 1024)).apply()
    }

    fun showReasoning(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_REASONING, true)

    fun setShowReasoning(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_REASONING, value).apply()
    }
}
