package dev.citali.needle

import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import dev.citali.needle.engine.ModelDownloadController
import dev.citali.needle.engine.NeedleEngine
import dev.citali.needle.engine.NeedlePrefs
import dev.citali.needle.remote.TelegramBridge
import dev.citali.needle.tools.ActivityBridges
import dev.citali.needle.ui.NeedleApp
import dev.citali.needle.ui.theme.NeedleTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The only Activity. It hosts the Compose UI and registers the two platform
 * flows that need a visible screen: camera capture and the biometric prompt.
 */
/**
 * [FragmentActivity] rather than a plain [androidx.activity.ComponentActivity] because
 * BiometricPrompt needs a fragment host to show its prompt.
 */
class MainActivity : FragmentActivity() {

    private var photoContinuation: CancellableContinuation<String>? = null
    private var photoTarget: File? = null
    private var biometricContinuation: CancellableContinuation<String>? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val continuation = photoContinuation
        val target = photoTarget
        photoContinuation = null
        photoTarget = null
        if (continuation == null || !continuation.isActive) return@registerForActivityResult
        continuation.resume(
            when {
                saved && target != null && target.length() > 0 -> "Photo saved to ${target.absolutePath}"
                saved -> "The camera app reported a photo but wrote no data."
                else -> "No photo was taken."
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (NeedlePrefs.telegramEnabled(this) && NeedlePrefs.telegramToken(this).isNotBlank()) {
            TelegramBridge.start(this, NeedlePrefs.telegramToken(this))
        }
        NeedleEngine.refresh(this)
        if (ModelDownloadController.state.value.total <= 0) {
            ModelDownloadController.clearMessage()
        }
        setContent {
            NeedleTheme {
                NeedleApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActivityBridges.photoCapture = { capturePhoto() }
        ActivityBridges.biometricCheck = { reason -> authenticate(reason) }
        NeedleEngine.refresh(this)
    }

    override fun onStop() {
        ActivityBridges.photoCapture = null
        ActivityBridges.biometricCheck = null
        super.onStop()
    }

    private suspend fun capturePhoto(): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val file = File(cacheDir, "photos/capture_${System.currentTimeMillis()}.jpg")
            file.parentFile?.mkdirs()
            val uri: Uri = runCatching {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            }.getOrElse { error ->
                continuation.resume("The camera file could not be prepared: ${error.message}")
                return@suspendCancellableCoroutine
            }
            photoContinuation = continuation
            photoTarget = file
            continuation.invokeOnCancellation {
                photoContinuation = null
                photoTarget = null
            }
            runCatching { takePicture.launch(uri) }.onFailure {
                photoContinuation = null
                photoTarget = null
                if (continuation.isActive) continuation.resume("No camera app accepted the request.")
            }
        }
    }

    private suspend fun authenticate(reason: String): String = withContext(Dispatchers.Main) {
        val canAuthenticate = BiometricManager.from(this@MainActivity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            return@withContext "No fingerprint or face unlock is set up on this device."
        }
        suspendCancellableCoroutine { continuation ->
            biometricContinuation = continuation
            continuation.invokeOnCancellation { biometricContinuation = null }
            val prompt = BiometricPrompt(
                this@MainActivity,
                ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        biometricContinuation = null
                        if (continuation.isActive) continuation.resume("Identity confirmed.")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        biometricContinuation = null
                        if (continuation.isActive) continuation.resume("Check cancelled: $errString")
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Needle")
                .setSubtitle(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Cancel")
                .build()
            runCatching { prompt.authenticate(info) }.onFailure { error ->
                biometricContinuation = null
                if (continuation.isActive) continuation.resume("The prompt could not be shown: ${error.message}")
            }
        }
    }
}
