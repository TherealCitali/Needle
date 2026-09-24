package dev.citali.needle.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** UI-facing state for the one-time model download. */
object ModelDownloadController {

    data class State(
        val running: Boolean = false,
        val written: Long = 0,
        val total: Long = ModelRepository.WEIGHTS_BYTES,
        val message: String? = null,
        val error: Boolean = false,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (written.toFloat() / total).coerceIn(0f, 1f)
        val writtenLabel: String get() = "${ModelRepository.humanSize(written)} / ${ModelRepository.humanSize(total)}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun download(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = State(running = true)
        job = scope.launch {
            ModelRepository.download(app) { written, total ->
                _state.value = _state.value.copy(running = true, written = written, total = total)
            }.fold(
                onSuccess = { file ->
                    NeedleEngine.unload()
                    NeedleEngine.refresh(app)
                    _state.value = State(
                        message = "The ${ModelRepository.humanSize(file.length())} Needle " +
                            "${ModelRepository.ENGINE_VERSION} model is verified and ready. " +
                            "Everything now runs on the phone.",
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        _state.value = State(message = "Download cancelled. It will resume where it stopped.")
                    } else {
                        _state.value = State(message = error.message ?: "Download failed.", error = true)
                    }
                },
            )
        }
    }

    fun import(context: Context, uri: Uri) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = State(running = true)
        job = scope.launch {
            ModelRepository.importFrom(app, uri) { written, total ->
                _state.value = _state.value.copy(running = true, written = written, total = total)
            }.fold(
                onSuccess = { file ->
                    NeedleEngine.unload()
                    NeedleEngine.refresh(app)
                    _state.value = State(message = "Imported and verified ${file.name}.")
                },
                onFailure = { error ->
                    _state.value = State(message = error.message ?: "Import failed.", error = true)
                },
            )
        }
    }

    fun delete(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = null
        scope.launch {
            ModelRepository.delete(app)
            NeedleEngine.unload()
            NeedleEngine.refresh(app)
            _state.value = State(message = "The model file was deleted from this device.")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = false)
    }
}
