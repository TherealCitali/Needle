package dev.citali.needle.pilot.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "needle_pilot_settings")

/**
 * User-tunable TaskPilot settings, persisted with Jetpack DataStore.
 *
 * Nothing in this file is secret. The API key is handled separately in
 * [SecureStore] and never touches this store.
 */
data class PilotSettings(
    val endpointUrl: String = DEFAULT_ENDPOINT,
    val apiPath: String = DEFAULT_API_PATH,
    val model: String = DEFAULT_MODEL,
    /** Models tried in order when the primary model fails; one per line in the UI. */
    val fallbackModels: List<String> = emptyList(),
    val highRiskConfirmations: Boolean = true,
    val redactSensitiveValues: Boolean = true,
    val pauseOnAmbiguity: Boolean = true,
    val showTreeSummary: Boolean = true,
    val showValidation: Boolean = true,
    val showOverlay: Boolean = true,
    /** Drive the automation loop with the on-device Needle model when it is loaded. */
    val useOnDeviceModel: Boolean = true,
) {
    /** The primary model followed by any fallbacks, de-duplicated. */
    val modelChain: List<String>
        get() = (listOf(model) + fallbackModels)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_API_PATH = "/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

object SettingsStore {
    private val KEY_ENDPOINT = stringPreferencesKey("endpoint_url")
    private val KEY_API_PATH = stringPreferencesKey("api_path")
    private val KEY_FALLBACK_MODELS = stringPreferencesKey("fallback_models")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_HIGH_RISK_CONFIRM = booleanPreferencesKey("high_risk_confirmations")
    private val KEY_REDACT = booleanPreferencesKey("redact_sensitive_values")
    private val KEY_PAUSE_AMBIGUITY = booleanPreferencesKey("pause_on_ambiguity")
    private val KEY_SHOW_TREE = booleanPreferencesKey("show_tree_summary")
    private val KEY_SHOW_VALIDATION = booleanPreferencesKey("show_validation")
    private val KEY_SHOW_OVERLAY = booleanPreferencesKey("show_overlay")
    private val KEY_ON_DEVICE = booleanPreferencesKey("use_on_device_model")

    fun settings(context: Context): Flow<PilotSettings> =
        context.settingsDataStore.data.map { p ->
            PilotSettings(
                endpointUrl = p[KEY_ENDPOINT] ?: PilotSettings.DEFAULT_ENDPOINT,
                apiPath = p[KEY_API_PATH]?.takeIf { it.isNotBlank() } ?: PilotSettings.DEFAULT_API_PATH,
                model = p[KEY_MODEL] ?: PilotSettings.DEFAULT_MODEL,
                fallbackModels = (p[KEY_FALLBACK_MODELS] ?: "")
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() },
                highRiskConfirmations = p[KEY_HIGH_RISK_CONFIRM] ?: true,
                redactSensitiveValues = p[KEY_REDACT] ?: true,
                pauseOnAmbiguity = p[KEY_PAUSE_AMBIGUITY] ?: true,
                showTreeSummary = p[KEY_SHOW_TREE] ?: true,
                showValidation = p[KEY_SHOW_VALIDATION] ?: true,
                showOverlay = p[KEY_SHOW_OVERLAY] ?: true,
                useOnDeviceModel = p[KEY_ON_DEVICE] ?: true,
            )
        }

    suspend fun snapshot(context: Context): PilotSettings = settings(context).first()

    suspend fun setProvider(
        context: Context,
        endpoint: String,
        model: String,
        apiPath: String = PilotSettings.DEFAULT_API_PATH,
        fallbackModels: List<String> = emptyList(),
    ) {
        context.settingsDataStore.edit { p ->
            p[KEY_ENDPOINT] = endpoint.trim()
            p[KEY_MODEL] = model.trim()
            p[KEY_API_PATH] = apiPath.trim().ifBlank { PilotSettings.DEFAULT_API_PATH }
            p[KEY_FALLBACK_MODELS] = fallbackModels
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }

    suspend fun setHighRiskConfirmations(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_HIGH_RISK_CONFIRM] = value }
    }

    suspend fun setRedactSensitiveValues(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_REDACT] = value }
    }

    suspend fun setPauseOnAmbiguity(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_PAUSE_AMBIGUITY] = value }
    }

    suspend fun setShowTreeSummary(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_TREE] = value }
    }

    suspend fun setShowValidation(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_VALIDATION] = value }
    }

    suspend fun setShowOverlay(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_OVERLAY] = value }
    }

    suspend fun setUseOnDeviceModel(context: Context, value: Boolean) {
        context.settingsDataStore.edit { it[KEY_ON_DEVICE] = value }
    }
}
