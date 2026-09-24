package dev.citali.needle.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.citali.needle.engine.ChatController
import dev.citali.needle.engine.ModelDownloadController
import dev.citali.needle.engine.NeedleEngine

private val exampleCommands = listOf(
    "turn on the flashlight",
    "what is my battery level?",
    "vibrate for 1 second",
    "copy Hello World to clipboard",
    "what network am I on?",
    "where am I right now?",
    "say out loud that the battery is low",
    "open WhatsApp",
)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages by ChatController.messages.collectAsStateWithLifecycle()
    val busy by ChatController.busy.collectAsStateWithLifecycle()
    val engine by NeedleEngine.state.collectAsStateWithLifecycle()
    val download by ModelDownloadController.state.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = spoken
        } else {
            ChatController.addSystem("No speech was recognised.", error = true)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxWidth().imePadding()) {
        EngineStatusCard(
            status = engine.status.name,
            detail = engine.detail,
            engineAvailable = engine.engineAvailable,
            modelLoaded = engine.modelLoaded,
            modelReady = engine.status != NeedleEngine.Status.MODEL_MISSING,
            downloadRunning = download.running,
            downloadFraction = download.fraction,
            downloadLabel = download.writtenLabel,
            downloadMessage = download.message,
            downloadError = download.error,
            onDownload = { if (download.running) ModelDownloadController.clearMessage() else ModelDownloadController.download(context) },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(
                            "Ask in plain English. The model runs on this phone, picks the tool it needs, " +
                                "and tells you what it did.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            exampleCommands.forEach { example ->
                                AssistChip(
                                    onClick = { input = example },
                                    label = { Text(example) },
                                )
                            }
                        }
                    }
                }
            }
            items(messages) { message -> MessageCard(message) }
            if (busy) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                        Text("Thinking on-device…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell Needle what to do") },
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
                    }
                    runCatching { micLauncher.launch(intent) }
                        .onFailure { ChatController.addSystem("No speech recogniser is available on this device.", error = true) }
                },
                enabled = !busy,
            ) { Icon(Icons.Filled.Mic, contentDescription = "Speak") }

            IconButton(
                onClick = {
                    ChatController.send(context, input)
                    input = ""
                },
                enabled = !busy && input.isNotBlank(),
            ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun EngineStatusCard(
    status: String,
    detail: String,
    engineAvailable: Boolean,
    modelLoaded: Boolean,
    modelReady: Boolean,
    downloadRunning: Boolean,
    downloadFraction: Float,
    downloadLabel: String,
    downloadMessage: String?,
    downloadError: Boolean,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("On-device Needle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !engineAvailable -> "not in this build"
                        modelLoaded -> "loaded"
                        else -> status.lowercase().replace('_', ' ')
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (modelLoaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!modelReady && engineAvailable) {
                if (downloadRunning) {
                    LinearProgressIndicator(
                        progress = { downloadFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(downloadLabel, style = MaterialTheme.typography.labelSmall)
                } else {
                    SecondaryButton(text = "Download the model", onClick = onDownload)
                }
            }
            downloadMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: ChatController.Message) {
    val isUser = message.role == ChatController.Role.USER
    val container = when {
        message.error -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        message.role == ChatController.Role.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (message.role) {
                    ChatController.Role.USER -> "You"
                    ChatController.Role.ASSISTANT -> "Needle"
                    ChatController.Role.SYSTEM -> "System"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
            if (message.tools.isNotEmpty()) {
                Text(
                    "Called: ${message.tools.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.results.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.results.forEach { result ->
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            message.reasoning?.let { reasoning ->
                Text(
                    "Reasoning: $reasoning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.confidence?.let { confidence ->
                Text(
                    "Confidence: $confidence%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }

}
