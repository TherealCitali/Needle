package dev.citali.needle.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.citali.needle.engine.ChatController
import dev.citali.needle.engine.ModelDownloadController
import dev.citali.needle.engine.ModelRepository
import dev.citali.needle.engine.NeedleEngine
import dev.citali.needle.engine.NeedlePrefs
import dev.citali.needle.pilot.data.PilotSettings
import dev.citali.needle.pilot.data.SecureStore
import dev.citali.needle.pilot.data.SettingsStore
import dev.citali.needle.remote.NeedleRemoteService
import dev.citali.needle.remote.TelegramBridge
import dev.citali.needle.tools.ActivityBridges
import dev.citali.needle.tools.DevicePermissions
import dev.citali.needle.tools.PhoneTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine by NeedleEngine.state.collectAsStateWithLifecycle()
    val download by ModelDownloadController.state.collectAsStateWithLifecycle()
    val telegram by TelegramBridge.state.collectAsStateWithLifecycle()
    val settingsFlow = remember(context) { SettingsStore.settings(context) }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = PilotSettings())

    var packs by remember { mutableStateOf(NeedlePrefs.toolPacks(context)) }
    var maxTokens by remember { mutableStateOf(NeedlePrefs.maxNewTokens(context).toFloat()) }
    var showReasoning by remember { mutableStateOf(NeedlePrefs.showReasoning(context)) }
    var telegramToken by remember { mutableStateOf(NeedlePrefs.telegramToken(context)) }
    var telegramEnabled by remember { mutableStateOf(NeedlePrefs.telegramEnabled(context)) }
    var endpointUrl by remember { mutableStateOf(settings.endpointUrl) }
    var apiPath by remember { mutableStateOf(settings.apiPath) }
    var model by remember { mutableStateOf(settings.model) }
    var fallbacks by remember { mutableStateOf(settings.fallbackModels.joinToString("\n")) }
    var apiKeyInput by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ModelDownloadController.import(context, uri)
    }

    LaunchedEffect(Unit) {
        hasApiKey = withContext(Dispatchers.IO) { SecureStore.hasApiKey(context) }
        NeedleEngine.refresh(context)
    }
    LaunchedEffect(settings.endpointUrl, settings.model) {
        if (endpointUrl.isBlank()) endpointUrl = settings.endpointUrl
        if (model.isBlank()) model = settings.model
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(
            title = "On-device model",
            subtitle = "Needle ${ModelRepository.ENGINE_VERSION} by Cactus Compute, " +
                "${ModelRepository.humanSize(ModelRepository.WEIGHTS_BYTES)} of weights, runs entirely offline.",
        ) {
            KeyValue("Engine", if (engine.engineAvailable) "available" else "not in this build")
            KeyValue("Status", engine.status.name.lowercase().replace('_', ' '))
            if (engine.detail.isNotBlank()) {
                Text(engine.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (download.running) {
                LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                Text(download.writtenLabel, style = MaterialTheme.typography.labelSmall)
                SecondaryButton(text = "Cancel", onClick = { ModelDownloadController.cancel() })
            } else {
                ActionRow {
                    PrimaryButton(text = "Download", onClick = { ModelDownloadController.download(context) })
                    SecondaryButton(text = "Import a .cact file", onClick = { importLauncher.launch(arrayOf("*/*")) })
                    OutlinedIconButton(
                        onClick = { ModelDownloadController.delete(context) },
                        enabled = ModelRepository.hasWeights(context),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete model weights",
                        )
                    }
                }
            }
            download.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (download.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                "SHA-256 ${ModelRepository.WEIGHTS_SHA256.take(24)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            title = "Tool packs",
            subtitle = "Every declared tool shares the model's context, so enable only what you need.",
        ) {
            PhoneTools.Pack.entries.forEach { pack ->
                ToggleRow(
                    title = pack.label,
                    subtitle = "${pack.summary} · ${PhoneTools.tools(context, setOf(pack)).size} tools",
                    checked = pack in packs,
                    onCheckedChange = { enabled ->
                        packs = if (enabled) packs + pack else packs - pack
                        if (packs.isEmpty()) packs = setOf(PhoneTools.Pack.CORE)
                        NeedlePrefs.setToolPacks(context, packs)
                        scope.launch { NeedleEngine.unload() }
                    },
                )
            }
            Text(
                "Changing packs reloads the tool catalogue on the next message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            title = "Remote control",
            subtitle = "Text the phone through a Telegram bot. Messages run the same on-device model.",
        ) {
            OutlinedTextField(
                value = telegramToken,
                onValueChange = { telegramToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bot token from @BotFather") },
                singleLine = true,
            )
            ActionRow {
                SecondaryButton(
                    text = "Save token",
                    enabled = telegramToken.isNotBlank(),
                    onClick = {
                        NeedlePrefs.setTelegramToken(context, telegramToken)
                        ChatController.addSystem("Telegram token saved.")
                    },
                )
                PrimaryButton(
                    text = if (telegramEnabled) "Turn off" else "Turn on",
                    enabled = telegramToken.isNotBlank() || NeedlePrefs.telegramToken(context).isNotBlank(),
                    onClick = {
                        val enable = !telegramEnabled
                        NeedlePrefs.setTelegramEnabled(context, enable)
                        telegramEnabled = enable
                        NeedlePrefs.setTelegramToken(context, telegramToken)
                        if (enable) {
                            NeedleRemoteService.start(context, telegramToken)
                        } else {
                            NeedleRemoteService.stop(context)
                            TelegramBridge.stop()
                        }
                    },
                )
            }
            KeyValue("Listener", if (telegram.running) "running" else "stopped")
            telegram.handled.takeIf { it > 0 }?.let { KeyValue("Commands handled", it.toString()) }
            telegram.lastMessage?.let { MonoBlock("last: $it") }
            telegram.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Section(
            title = "Automation",
            subtitle = "How the screen-automation loop decides and what it asks first.",
        ) {
            ToggleRow(
                title = "Use the on-device model",
                subtitle = "Otherwise tasks need a remote provider, or fall back to the built-in routines.",
                checked = settings.useOnDeviceModel,
                onCheckedChange = { scope.launch { SettingsStore.setUseOnDeviceModel(context, it) } },
            )
            ToggleRow(
                title = "Redact sensitive values",
                subtitle = "Passwords, OTPs, card numbers and IDs are masked before the model sees them.",
                checked = settings.redactSensitiveValues,
                onCheckedChange = { scope.launch { SettingsStore.setRedactSensitiveValues(context, it) } },
            )
            ToggleRow(
                title = "Confirm high-risk steps",
                subtitle = "Sending, deleting, paying or granting permissions asks again.",
                checked = settings.highRiskConfirmations,
                onCheckedChange = { scope.launch { SettingsStore.setHighRiskConfirmations(context, it) } },
            )
            ToggleRow(
                title = "Floating stop button",
                subtitle = "Shows the TaskPilot pill over other apps while a task runs.",
                checked = settings.showOverlay,
                onCheckedChange = { scope.launch { SettingsStore.setShowOverlay(context, it) } },
            )
            ToggleRow(
                title = "Log the screen summary",
                subtitle = "Adds a redacted node count for each observation.",
                checked = settings.showTreeSummary,
                onCheckedChange = { scope.launch { SettingsStore.setShowTreeSummary(context, it) } },
            )
            ToggleRow(
                title = "Log safety validation",
                subtitle = "Explains each action's risk classification.",
                checked = settings.showValidation,
                onCheckedChange = { scope.launch { SettingsStore.setShowValidation(context, it) } },
            )
            ActionRow {
                SecondaryButton(
                    text = "Accessibility settings",
                    onClick = { DevicePermissions.openAccessibilitySettings(context) },
                )
                SecondaryButton(
                    text = "App permissions",
                    onClick = { DevicePermissions.openAppSettings(context) },
                )
            }
        }

        Section(
            title = "Remote AI provider",
            subtitle = "Optional. Any OpenAI-compatible endpoint is used only when the on-device model is off.",
        ) {
            OutlinedTextField(
                value = endpointUrl,
                onValueChange = { endpointUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                singleLine = true,
            )
            OutlinedTextField(
                value = apiPath,
                onValueChange = { apiPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chat path") },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true,
            )
            OutlinedTextField(
                value = fallbacks,
                onValueChange = { fallbacks = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fallback models (one per line)") },
                maxLines = 3,
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasApiKey) "Replace API key (stored encrypted)" else "API key") },
                singleLine = true,
            )
            ActionRow {
                PrimaryButton(
                    text = "Save provider",
                    onClick = {
                        scope.launch {
                            SettingsStore.setProvider(
                                context,
                                endpointUrl,
                                model,
                                apiPath,
                                fallbacks.split('\n').map { it.trim() }.filter { it.isNotBlank() },
                            )
                            if (apiKeyInput.isNotBlank()) {
                                val saved = withContext(Dispatchers.IO) { SecureStore.saveApiKey(context, apiKeyInput) }
                                hasApiKey = withContext(Dispatchers.IO) { SecureStore.hasApiKey(context) }
                                apiKeyInput = ""
                                ChatController.addSystem(if (saved) "Provider saved; the key is encrypted with the device keystore." else "The key could not be stored.")
                            } else {
                                ChatController.addSystem("Provider settings saved.")
                            }
                        }
                    },
                )
                SecondaryButton(
                    text = "Clear key",
                    enabled = hasApiKey,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { SecureStore.clearApiKey(context) }
                            hasApiKey = false
                            ChatController.addSystem("Stored API key removed.")
                        }
                    },
                )
            }
            KeyValue("Key stored", if (hasApiKey) "yes" else "no")
        }

        Section(title = "Answer length", subtitle = "Longer answers reserve more of the model's context.") {
            Text("Max new tokens: ${maxTokens.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                valueRange = 128f..1024f,
                onValueChangeFinished = { NeedlePrefs.setMaxNewTokens(context, maxTokens.toInt()) },
            )
            ToggleRow(
                title = "Show reasoning and confidence",
                subtitle = "Both come from the model itself on every turn.",
                checked = showReasoning,
                onCheckedChange = {
                    showReasoning = it
                    NeedlePrefs.setShowReasoning(context, it)
                },
            )
        }

        Section(title = "About") {
            KeyValue("Engine", "Needle ${ModelRepository.ENGINE_VERSION} · Apache-2.0 · Cactus Compute")
            KeyValue("Automation core", "TaskPilot (MIT) by TherealCitali")
            Text(
                "Needle translates your words into phone actions on this device. " +
                    "Nothing is uploaded: the model, the accessibility snapshots and the history all stay here. " +
                    "Camera and fingerprint checks are the only actions that need you on screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValue("Foreground app hooks", if (ActivityBridges.hasCamera) "active" else "inactive")
        }
    }
}
