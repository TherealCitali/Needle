package dev.citali.needle.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.citali.needle.engine.EngineBrain
import dev.citali.needle.engine.NeedleEngine
import dev.citali.needle.pilot.agent.AgentEngine
import dev.citali.needle.pilot.agent.AppSpec
import dev.citali.needle.pilot.agent.CommandPlanner
import dev.citali.needle.pilot.agent.Plan
import dev.citali.needle.pilot.accessibility.NeedleAccessibilityService
import dev.citali.needle.pilot.data.AppInventory
import dev.citali.needle.pilot.data.HistoryStore
import dev.citali.needle.pilot.data.SecureStore
import dev.citali.needle.tools.DevicePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TASKPILOT_PACKAGE = "dev.citali.taskpilot"

@Composable
fun PilotScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AgentEngine.state.collectAsStateWithLifecycle()
    val engineState by NeedleEngine.state.collectAsStateWithLifecycle()
    val historyFlow = remember(context) { HistoryStore.entries(context) }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val onDeviceReady = engineState.status == NeedleEngine.Status.READY

    var command by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var accessibilityOn by remember { mutableStateOf(NeedleAccessibilityService.isConnected()) }
    var remoteProvider by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase, state.serviceConnected) {
        accessibilityOn = NeedleAccessibilityService.isConnected()
    }
    LaunchedEffect(Unit) {
        remoteProvider = withContext(Dispatchers.IO) {
            !SecureStore.getApiKey(context).isNullOrBlank()
        }
    }

    fun buildPlan(): Plan = CommandPlanner.parse(
        command = command,
        aiAvailable = onDeviceReady || remoteProvider,
        resolveApp = { query: String ->
            AppInventory.resolve(context, query)?.let { app -> AppSpec(app.packageName, app.label) }
        },
    )

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(
            title = "Screen automation",
            subtitle = "TaskPilot's approval loop with the on-device model. One validated action at a time.",
        ) {
            KeyValue("Accessibility service", if (accessibilityOn) "enabled" else "off")
            KeyValue(
                "Decision engine",
                when {
                    onDeviceReady -> "on-device Needle ${EngineBrain.engineVersion()}"
                    remoteProvider -> "remote provider"
                    else -> "built-in routines only"
                },
            )
            if (!accessibilityOn) {
                SecondaryButton(text = "Open accessibility settings", onClick = {
                    DevicePermissions.openAccessibilitySettings(context)
                })
            }
        }

        Section(
            title = "Task",
            subtitle = "Describe the job in the words you would use with a person.",
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Open Settings and turn on battery saver") },
                maxLines = 3,
                enabled = !AgentEngine.isActive(),
            )
            ActionRow {
                PrimaryButton(
                    text = "Review plan",
                    enabled = command.isNotBlank() && !AgentEngine.isActive(),
                    onClick = { plan = buildPlan() },
                )
                SecondaryButton(
                    text = "Approve and run",
                    enabled = command.isNotBlank() && !AgentEngine.isActive() && accessibilityOn,
                    onClick = {
                        val ready = plan ?: buildPlan()
                        command = ready.command
                        AgentEngine.start(context, ready)
                    },
                )
            }
            if (isTaskPilotInstalled(context)) {
                TextButton(onClick = {
                    copyToClipboard(context, command)
                    context.packageManager.getLaunchIntentForPackage(TASKPILOT_PACKAGE)?.let { launch ->
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(launch) }
                    }
                }) { Text("Hand off to the TaskPilot app (command copied)") }
            }

            plan?.let { current ->
                HorizontalDivider()
                Text("Plan · ${current.steps.size} steps", fontWeight = FontWeight.SemiBold)
                current.steps.forEach { step ->
                    Text(
                        "• ${step.title}" + if (step.highRisk) "   [high-risk]" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "   ${step.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (current.subGoals.isNotEmpty()) {
                    Text("Stages", fontWeight = FontWeight.SemiBold)
                    current.subGoals.forEachIndexed { index, goal -> Text("${index + 1}. $goal") }
                }
                ActionRow {
                    PrimaryButton(
                        text = "Approve and run",
                        enabled = accessibilityOn && !AgentEngine.isActive(),
                        onClick = { AgentEngine.start(context, current) },
                    )
                    SecondaryButton(text = "Discard", onClick = { plan = null })
                }
                if (!accessibilityOn) {
                    Text(
                        "Turn on the Needle accessibility service first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (state.phase != AgentEngine.Phase.IDLE) {
            Section(title = "Run · ${state.phase.name.lowercase()}", subtitle = state.statusText) {
                state.question?.let { question ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (question.highRisk) "Confirm a high-risk step" else "Needle needs an answer",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(question.text, style = MaterialTheme.typography.bodyMedium)
                            ActionRow {
                                PrimaryButton(text = "Approve", onClick = { AgentEngine.answer(true) })
                                SecondaryButton(text = "Decline", onClick = { AgentEngine.answer(false) })
                            }
                        }
                    }
                }
                ActionRow {
                    if (AgentEngine.isActive()) {
                        SecondaryButton(text = "Stop", onClick = { AgentEngine.stop() })
                        if (state.phase == AgentEngine.Phase.PAUSED) {
                            SecondaryButton(text = "Resume", onClick = { AgentEngine.resume() })
                        } else {
                            SecondaryButton(text = "Pause", onClick = { AgentEngine.pause() })
                        }
                    } else {
                        SecondaryButton(text = "Clear", onClick = { AgentEngine.reset() })
                    }
                }
                state.log.takeLast(24).forEach { entry ->
                    Text(
                        "${timeLabel(entry.timeMillis)}  ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.level) {
                            AgentEngine.LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            AgentEngine.LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            AgentEngine.LogLevel.ACTION -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Section(title = "Recent tasks", subtitle = "Only the command text and its outcome are stored.") {
                history.take(6).forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            entry.command.take(46),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { scope.launch { HistoryStore.clear(context) } }) { Text("Clear history") }
            }
        }
    }
}

private fun isTaskPilotInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getLaunchIntentForPackage(TASKPILOT_PACKAGE) != null }.getOrDefault(false)

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Needle", text))
    }
}

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
