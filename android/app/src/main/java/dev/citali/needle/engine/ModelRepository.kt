package dev.citali.needle.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.citali.needle.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The Needle weight archive (`needle3.cact`).
 *
 * The archive is 35 MB, so it is not shipped in the APK: it is downloaded once,
 * checksum-verified against the hash the build was compiled with, and then used
 * offline forever. Partial downloads resume, and a verified file can also be
 * imported from device storage for air-gapped installs.
 */
object ModelRepository {

    private const val TAG = "NeedleModel"
    private const val MODELS_DIR = "models"
    private const val FILE_NAME = "needle3.cact"

    const val WEIGHTS_BYTES: Long = BuildConfig.NEEDLE_WEIGHTS_SIZE
    const val WEIGHTS_SHA256: String = BuildConfig.NEEDLE_WEIGHTS_SHA256
    val WEIGHTS_URL: String = BuildConfig.NEEDLE_WEIGHTS_URL
    val ENGINE_VERSION: String = BuildConfig.NEEDLE_ENGINE_VERSION

    fun weightsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    fun weightsFile(context: Context): File = File(weightsDir(context), FILE_NAME)

    fun partialFile(context: Context): File = File(weightsDir(context), "$FILE_NAME.part")

    fun hasWeights(context: Context): Boolean {
        val file = weightsFile(context)
        return file.isFile && file.length() == WEIGHTS_BYTES
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    /** Streams and checksum-verifies the model already on disk. */
    suspend fun verify(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = weightsFile(context)
        if (!file.isFile) return@withContext false
        if (file.length() != WEIGHTS_BYTES) return@withContext false
        sha256(file) == WEIGHTS_SHA256
    }

    /**
     * Downloads (or resumes) the archive. [onProgress] receives bytes written and
     * the expected total. Returns the verified file on success.
     */
    suspend fun download(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = weightsDir(context).apply { mkdirs() }
            if (!dir.isDirectory) error("Cannot create ${dir.absolutePath}")
            val target = weightsFile(context)
            val partial = partialFile(context)
            if (target.isFile && target.length() == WEIGHTS_BYTES) {
                onProgress(WEIGHTS_BYTES, WEIGHTS_BYTES)
                return@runCatching target
            }
            target.delete()

            downloadToPartial(partial, onProgress)

            val digest = sha256(partial)
            if (digest != WEIGHTS_SHA256) {
                partial.delete()
                error("The downloaded model failed its checksum check. Please try again.")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) error("Could not move the model into place.")
            Log.i(TAG, "model verified: ${target.absolutePath}")
            target
        }
    }

    /** Streams the archive into [partial], resuming a previous attempt when possible. */
    private suspend fun downloadToPartial(
        partial: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        var existing = if (partial.isFile) partial.length() else 0L
        var attempt = 0
        while (attempt < 3) {
            attempt++
            coroutineContext.ensureActive()
            val connection = (URL(WEIGHTS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Needle-Android/${BuildConfig.VERSION_NAME}")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK && existing > 0) {
                    // The server ignored the range request; start over.
                    partial.delete()
                    existing = 0L
                } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("HTTP $code from the model host. ${detail.take(160)}")
                }
                val remaining = connection.contentLengthLong.takeIf { it > 0 }
                val total = if (remaining != null) existing + remaining else WEIGHTS_BYTES
                connection.inputStream.use { input ->
                    RandomAccessFile(partial, "rw").use { output ->
                        output.seek(existing)
                        copyStream(input, output, existing, total, onProgress)
                    }
                }
                if (partial.length() != WEIGHTS_BYTES) {
                    existing = partial.length()
                    error("Incomplete download (${partial.length()} of $WEIGHTS_BYTES bytes)")
                }
                onProgress(WEIGHTS_BYTES, WEIGHTS_BYTES)
                return
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "download attempt $attempt failed: ${error.message}")
                existing = if (partial.isFile) partial.length() else 0L
                if (attempt >= 3) throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Copies a `.cact` archive the user picked, then verifies it. */
    suspend fun importFrom(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            weightsDir(context).mkdirs()
            val partial = partialFile(context)
            partial.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    copyStream(input, output, 0, WEIGHTS_BYTES, onProgress)
                }
            } ?: error("Could not read the selected file.")
            val digest = sha256(partial)
            if (digest != WEIGHTS_SHA256) {
                val read = partial.length()
                partial.delete()
                error(
                    "That file is not the ${humanSize(WEIGHTS_BYTES)} Needle $ENGINE_VERSION archive " +
                        "(read $read bytes, expected SHA-256 ${WEIGHTS_SHA256.take(12)}…)."
                )
            }
            val target = weightsFile(context)
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) error("Could not move the model into place.")
            target
        }
    }

    fun delete(context: Context) {
        weightsFile(context).delete()
        partialFile(context).delete()
    }

    private fun copyStream(
        input: InputStream,
        output: RandomAccessFile,
        alreadyWritten: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(256 * 1024)
        var written = alreadyWritten
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            onProgress(written, total)
        }
        output.setLength(written)
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
