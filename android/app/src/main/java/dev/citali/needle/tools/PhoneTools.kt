package dev.citali.needle.tools

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.citali.needle.engine.NeedleParam
import dev.citali.needle.engine.NeedleTool
import dev.citali.needle.pilot.data.AppInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The phone tools the model can call.
 *
 * These are native Android equivalents of the `termux-*` commands the original
 * Python app shelled out to: no Termux, no Termux:API app, no root. Each tool
 * checks its own permissions and answers with a readable sentence when something
 * is missing instead of failing silently.
 */
object PhoneTools {

    enum class Pack(val label: String, val summary: String) {
        CORE("Essentials", "Toasts, notifications, speech, clipboard, vibration, torch, battery"),
        DEVICE("Device & screen", "Location, Wi-Fi, brightness, volume, apps, downloads, screenshot"),
        COMMS("Calls & messages", "Send SMS, read inbox, contacts, call log, dial, share"),
        MEDIA("Camera & identity", "Camera capture and fingerprint check"),
    }

    val defaultPacks: Set<Pack> = setOf(Pack.CORE, Pack.DEVICE, Pack.COMMS)

    fun tools(context: Context, packs: Set<Pack>): List<NeedleTool> {
        val app = context.applicationContext
        val all = buildList {
            if (Pack.CORE in packs) addAll(coreTools(app))
            if (Pack.DEVICE in packs) addAll(deviceTools(app))
            if (Pack.COMMS in packs) addAll(commsTools(app))
            if (Pack.MEDIA in packs) addAll(mediaTools(app))
        }
        return all
    }

    fun toolNames(context: Context, packs: Set<Pack>): List<String> = tools(context, packs).map { it.name }

    /** Runs one tool directly, for the quick actions in the Tools tab. */
    suspend fun run(context: Context, packs: Set<Pack>, name: String, args: JSONObject = JSONObject()): String {
        val tool = tools(context, packs).firstOrNull { it.name == name } ?: return "Unknown tool: $name"
        return runCatching { tool.handler(args) }
            .getOrElse { error -> error.message ?: "The tool failed." }
    }

    /** Names of the tools a pack selection exposes, in declaration order. */
    fun describe(context: Context, packs: Set<Pack>): List<String> =
        tools(context, packs).map { "${it.name} — ${it.description}" }

    // ---- Essentials -------------------------------------------------------

    private fun coreTools(context: Context): List<NeedleTool> = listOf(
        tool(
            name = "show_toast",
            description = "Show a short popup message on the phone screen.",
            params = listOf(stringParam("message", "Text to display.")),
        ) { args ->
            val message = args.require("message")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            "Toast shown: $message"
        },

        tool(
            name = "show_notification",
            description = "Post a notification with a title and body to the notification shade.",
            params = listOf(
                stringParam("title", "Notification title."),
                stringParam("content", "Notification body."),
            ),
        ) { args ->
            notify(context, args.require("title"), args.require("content"))
        },

        tool(
            name = "speak",
            description = "Read text out loud with the phone's text-to-speech voice.",
            params = listOf(stringParam("text", "Text to speak.")),
        ) { args ->
            Speech.speak(context, args.require("text"))
        },

        tool(
            name = "set_clipboard",
            description = "Copy text to the system clipboard.",
            params = listOf(stringParam("text", "Text to copy.")),
        ) { args ->
            val text = args.require("text")
            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Needle", text))
            }
            "Copied to the clipboard."
        },

        tool(
            name = "get_clipboard",
            description = "Read the text currently on the system clipboard.",
        ) {
            val text = withContext(Dispatchers.Main) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            }
            if (text.isNullOrBlank()) "The clipboard is empty." else "Clipboard: $text"
        },

        tool(
            name = "vibrate",
            description = "Vibrate the phone for a number of milliseconds.",
            params = listOf(intParam("milliseconds", "How long to vibrate, 50 to 10000.", required = false)),
        ) { args ->
            if (!hasPermission(context, Manifest.permission.VIBRATE)) {
                return@tool "The VIBRATE permission has not been granted to Needle."
            }
            val duration = args.optInt("milliseconds", 500).coerceIn(50, 10_000).toLong()
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return@tool "This device has no vibrator."
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            "Vibrated for ${duration}ms."
        },

        tool(
            name = "set_flashlight",
            description = "Turn the camera flashlight (torch) on or off.",
            params = listOf(boolParam("on", "true to switch the flashlight on, false for off.")),
        ) { args ->
            val on = args.optBoolean("on", false)
            val cameraManager = context.getSystemService(CameraManager::class.java)
                ?: return@tool "No camera service on this device."
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@tool "This device has no camera flashlight."
            cameraManager.setTorchMode(cameraId, on)
            "Flashlight turned ${if (on) "on" else "off"}."
        },

        tool(
            name = "get_battery_status",
            description = "Report battery level, charging state, health and temperature.",
        ) {
            batteryJson(context)
        },
    )

    // ---- Device & screen --------------------------------------------------

    private fun deviceTools(context: Context): List<NeedleTool> = listOf(
        tool(
            name = "get_location",
            description = "Report the phone's current location as latitude and longitude.",
        ) {
            locationJson(context)
        },

        tool(
            name = "get_wifi_info",
            description = "Report the Wi-Fi network the phone is connected to (SSID, IP, signal).",
        ) {
            wifiJson(context)
        },

        tool(
            name = "scan_wifi_networks",
            description = "List nearby Wi-Fi networks with signal strength.",
            params = listOf(intParam("limit", "How many networks to list (1 to 20).", required = false)),
        ) { args ->
            scanWifi(context, args.optInt("limit", 8).coerceIn(1, 20))
        },

        tool(
            name = "get_device_info",
            description = "Report the phone model, Android version, carrier and SIM state.",
        ) {
            deviceJson(context)
        },

        tool(
            name = "set_brightness",
            description = "Set the screen brightness, 1 (dimmest) to 255 (brightest).",
            params = listOf(intParam("level", "Brightness from 1 to 255.")),
        ) { args ->
            val level = args.requireInt("level").coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                "Needle needs the \"Modify system settings\" permission to change brightness. " +
                    "Grant it from the Tools tab, then ask again."
            } else {
                val resolver = context.contentResolver
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
                "Screen brightness set to $level."
            }
        },

        tool(
            name = "get_volume",
            description = "Report the current volume of the phone's audio streams.",
        ) {
            val audio = context.getSystemService(AudioManager::class.java)
                ?: return@tool "No audio service on this device."
            val array = JSONArray()
            streamNames.forEach { (stream, label) ->
                array.put(
                    JSONObject()
                        .put("stream", label)
                        .put("volume", audio.getStreamVolume(stream))
                        .put("max_volume", audio.getStreamMaxVolume(stream))
                )
            }
            array.toString()
        },

        tool(
            name = "set_volume",
            description = "Set the volume of one audio stream.",
            params = listOf(
                NeedleParam(
                    name = "stream",
                    description = "Which stream to change.",
                    enumValues = listOf("music", "ring", "alarm", "notification", "system", "call"),
                ),
                intParam("volume", "New volume level."),
            ),
        ) { args ->
            val audio = context.getSystemService(AudioManager::class.java)
                ?: return@tool "No audio service on this device."
            val streamName = args.require("stream").lowercase(Locale.ROOT)
            val stream = streamNames.entries.firstOrNull { it.value == streamName }?.key
                ?: return@tool "Unknown stream '$streamName'. Use music, ring, alarm, notification, system or call."
            val max = audio.getStreamMaxVolume(stream)
            val value = args.requireInt("volume").coerceIn(0, max)
            audio.setStreamVolume(stream, value, 0)
            "Set $streamName volume to $value of $max."
        },

        tool(
            name = "take_screenshot",
            description = "Take a screenshot with the accessibility service (saved to the Screenshots folder).",
        ) {
            val service = dev.citali.needle.pilot.accessibility.NeedleAccessibilityService.instance
                ?: return@tool "The Needle accessibility service is off. Turn it on in Settings → Accessibility and try again."
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@tool "Screenshots need Android 11 or newer."
            }
            if (service.takeScreenshot()) {
                "Screenshot requested; it is saved in the Screenshots folder."
            } else {
                "Android refused the screenshot request."
            }
        },

        tool(
            name = "open_app",
            description = "Open an installed app by name, e.g. WhatsApp or Settings.",
            params = listOf(stringParam("app_name", "The app the user wants to open.")),
        ) { args ->
            val name = args.require("app_name")
            val app = AppInventory.resolve(context, name)
                ?: return@tool "\"$name\" is not installed on this device."
            if (!app.enabled) return@tool "${app.label} is disabled, so it cannot be opened."
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return@tool "${app.label} has no launcher screen."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opened ${app.label}."
        },

        tool(
            name = "open_url",
            description = "Open a web link in the phone's browser.",
            params = listOf(stringParam("url", "The link to open.")),
        ) { args ->
            val raw = args.require("url")
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opened $url."
        },

        tool(
            name = "download_file",
            description = "Download a file with the system download manager.",
            params = listOf(
                stringParam("url", "Direct link to the file."),
                stringParam("title", "Name to show in the notification.", required = false),
            ),
        ) { args ->
            val url = args.require("url")
            val title = args.optString("title").ifBlank { "Needle download" }
            val manager = context.getSystemService(DownloadManager::class.java)
                ?: return@tool "No download manager on this device."
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            runCatching {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: title)
            }
            val id = manager.enqueue(request)
            "Download started (id $id); it continues in the background."
        },
    )

    // ---- Calls & messages -------------------------------------------------

    private fun commsTools(context: Context): List<NeedleTool> = listOf(
        tool(
            name = "send_sms",
            description = "Send a text message to a phone number.",
            params = listOf(
                stringParam("recipient", "Phone number to text."),
                stringParam("message", "The message body."),
            ),
        ) { args ->
            if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
                return@tool "Needle has no SMS permission. Grant it from the Tools tab first."
            }
            val recipient = args.require("recipient")
            val message = args.require("message")
            val sms = smsManager(context) ?: return@tool "No telephony service on this device."
            val parts = sms.divideMessage(message)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                sms.sendTextMessage(recipient, null, message, null, null)
            }
            "SMS sent to $recipient."
        },

        tool(
            name = "read_sms",
            description = "Read the most recent text messages from the inbox.",
            params = listOf(intParam("limit", "How many messages to read (1 to 20).", required = false)),
        ) { args ->
            readSms(context, args.optInt("limit", 5).coerceIn(1, 20))
        },

        tool(
            name = "list_contacts",
            description = "List saved contacts, optionally filtered by name.",
            params = listOf(
                stringParam("query", "Part of a name to look for.", required = false),
                intParam("limit", "How many contacts to list (1 to 50).", required = false),
            ),
        ) { args ->
            listContacts(context, args.optString("query"), args.optInt("limit", 10).coerceIn(1, 50))
        },

        tool(
            name = "get_call_log",
            description = "Read the recent call history.",
            params = listOf(intParam("limit", "How many entries to read (1 to 20).", required = false)),
        ) { args ->
            readCallLog(context, args.optInt("limit", 5).coerceIn(1, 20))
        },

        tool(
            name = "make_call",
            description = "Phone a number, or open the dialer pre-filled when calling is not permitted.",
            params = listOf(stringParam("phone_number", "The number to call.")),
        ) { args ->
            val number = args.require("phone_number")
            if (hasPermission(context, Manifest.permission.CALL_PHONE)) {
                val telecom = context.getSystemService(TelecomManager::class.java)
                    ?: return@tool "No telecom service on this device."
                telecom.placeCall(Uri.parse("tel:$number"), Bundle())
                "Calling $number."
            } else {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened the dialer with $number. Grant the phone permission to place calls directly."
            }
        },

        tool(
            name = "share_text",
            description = "Open the Android share sheet with some text.",
            params = listOf(stringParam("text", "Text to share.")),
        ) { args ->
            val text = args.require("text")
            runCatching {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(Intent.createChooser(send, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Share sheet opened."
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Needle", text))
                }
                "Android blocked the share sheet from the background, so the text was copied to the clipboard instead."
            }
        },
    )

    // ---- Camera & identity ------------------------------------------------

    private fun mediaTools(context: Context): List<NeedleTool> = listOf(
        tool(
            name = "take_photo",
            description = "Take a photo with the camera. Ask the user to hold the phone steady, then confirm.",
            params = listOf(
                NeedleParam(
                    name = "confirm",
                    type = "boolean",
                    description = "true once the user has agreed to open the camera.",
                )
            ),
        ) { args ->
            val bridge = ActivityBridges.photoCapture
                ?: return@tool "Open the Needle app and take the photo from the Tools tab; the camera needs a visible screen."
            if (!args.optBoolean("confirm", false)) {
                return@tool "Photos need the user to confirm first. Ask them, then call this tool with confirm=true."
            }
            bridge()
        },

        tool(
            name = "authenticate_user",
            description = "Ask the device to confirm the owner with fingerprint or face unlock.",
            params = listOf(stringParam("reason", "Why the check is needed.", required = false)),
        ) { args ->
            val bridge = ActivityBridges.biometricCheck
                ?: return@tool "Fingerprint checks need the Needle app open on screen."
            bridge(args.optString("reason").ifBlank { "Confirm it is you" })
        },
    )

    // ---- Helpers ----------------------------------------------------------

    private val streamNames: Map<Int, String> = mapOf(
        AudioManager.STREAM_MUSIC to "music",
        AudioManager.STREAM_RING to "ring",
        AudioManager.STREAM_ALARM to "alarm",
        AudioManager.STREAM_NOTIFICATION to "notification",
        AudioManager.STREAM_SYSTEM to "system",
        AudioManager.STREAM_VOICE_CALL to "call",
    )

    private fun smsManager(context: Context): SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }

    private fun notify(context: Context, title: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return "Notification permission is missing. Grant it from the Tools tab, then ask again."
        }
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannelCompat.Builder("needle-messages", NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Needle")
            .setDescription("Messages and confirmations from the on-device assistant")
            .build()
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, "needle-messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .build()
        return runCatching {
            manager.notify(System.currentTimeMillis().toInt(), notification)
            "Notification posted: $title"
        }.getOrElse { error -> "Could not post the notification: ${error.message}" }
    }

    private fun batteryJson(context: Context): String {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.let { it / 10.0 }
        val manager = context.getSystemService(BatteryManager::class.java)
        val currentNow = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val json = JSONObject()
            .put("percentage", percent)
            .put("status", batteryStatusName(status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1))
            .put("plugged", pluggedName(status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0))
            .put("health", healthName(status?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1))
        temperature?.let { json.put("temperature_c", it) }
        currentNow?.takeIf { it != Int.MIN_VALUE }?.let { json.put("current_ua", it) }
        return json.toString()
    }

    private fun batteryStatusName(value: Int): String = when (value) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }

    private fun pluggedName(value: Int): String = when {
        value and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
        value and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
        value and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
        else -> "unplugged"
    }

    private fun healthName(value: Int): String = when (value) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private suspend fun locationJson(context: Context): String {
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return "Location permission is missing. Grant it from the Tools tab, then ask again."
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return "No location service on this device."
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        val fresh = last ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(12_000) { currentLocation(manager) }
        } else {
            null
        } ?: return "The phone has not fixed a location yet. Turn on location services and try again."

        return JSONObject()
            .put("latitude", fresh.latitude)
            .put("longitude", fresh.longitude)
            .put("accuracy_m", fresh.accuracy.roundToInt())
            .put("provider", fresh.provider ?: "unknown")
            .put("age_seconds", ((System.currentTimeMillis() - fresh.time) / 1000).coerceAtLeast(0))
            .toString()
    }

    private suspend fun currentLocation(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val executor = Executors.newSingleThreadExecutor()
            continuation.invokeOnCancellation { executor.shutdown() }
            runCatching {
                manager.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    null,
                    executor,
                    java.util.function.Consumer { location ->
                        executor.shutdown()
                        if (continuation.isActive) continuation.resume(location)
                    },
                )
            }.onFailure {
                executor.shutdown()
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private suspend fun wifiJson(context: Context): String {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return "No Wi-Fi service on this device."
        return try {
            @Suppress("DEPRECATION")
            val info = manager.connectionInfo ?: return "The phone is not connected to Wi-Fi."
            val ssid = info.ssid?.trim('"').orEmpty()
            if (ssid.isBlank() || ssid == "<unknown ssid>") {
                "Wi-Fi details are unavailable. Android needs the location permission for that; grant it from the Tools tab."
            } else {
                JSONObject()
                    .put("ssid", ssid)
                    .put("bssid", info.bssid)
                    .put("ip_address", ipv4(info.ipAddress))
                    .put("link_speed_mbps", info.linkSpeed)
                    .put("rssi_dbm", info.rssi)
                    .put("frequency_mhz", info.frequency)
                    .toString()
            }
        } catch (error: SecurityException) {
            "Wi-Fi details need the location permission. Grant it from the Tools tab."
        }
    }

    private fun scanWifi(context: Context, limit: Int): String {
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "Scanning Wi-Fi needs the location permission. Grant it from the Tools tab."
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return "No Wi-Fi service on this device."
        return try {
            @Suppress("DEPRECATION")
            manager.startScan()
            @Suppress("DEPRECATION")
            val results = manager.scanResults
            if (results.isEmpty()) {
                "No networks found. Android throttles Wi-Fi scans, so try again in a moment."
            } else {
                val array = JSONArray()
                results.sortedByDescending { it.level }.take(limit).forEach { result ->
                    array.put(
                        JSONObject()
                            .put("ssid", result.SSID)
                            .put("rssi_dbm", result.level)
                            .put("frequency_mhz", result.frequency)
                    )
                }
                array.toString()
            }
        } catch (error: SecurityException) {
            "Scanning Wi-Fi needs the location permission. Grant it from the Tools tab."
        }
    }

    private fun deviceJson(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val json = JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
        runCatching { telephony?.networkOperatorName?.takeIf { it.isNotBlank() }?.let { json.put("carrier", it) } }
        runCatching {
            json.put(
                "sim_state",
                when (telephony?.simState) {
                    TelephonyManager.SIM_STATE_READY -> "ready"
                    TelephonyManager.SIM_STATE_ABSENT -> "absent"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin required"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk required"
                    null -> "unknown"
                    else -> "unavailable"
                },
            )
            json.put("network_type", networkTypeName(telephony?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN))
        }
        return json.toString()
    }

    private fun networkTypeName(value: Int): String = when (value) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
        else -> "other"
    }

    private fun readSms(context: Context, limit: Int): String {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return "Reading messages needs the SMS permission. Grant it from the Tools tab."
        }
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val array = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext() && array.length() < limit) {
                array.put(
                    JSONObject()
                        .put("from", cursor.getString(addressIndex) ?: "unknown")
                        .put("body", cursor.getString(bodyIndex).orEmpty().take(240))
                        .put("received_at", cursor.getLong(dateIndex))
                )
            }
        }
        return if (array.length() == 0) "No messages found." else array.toString()
    }

    private fun listContacts(context: Context, query: String?, limit: Int): String {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return "Reading contacts needs the contacts permission. Grant it from the Tools tab."
        }
        val selection = if (query.isNullOrBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (query.isNullOrBlank()) null else arrayOf("%$query%")
        val array = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            selection,
            selectionArgs,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && array.length() < limit) {
                array.put(
                    JSONObject()
                        .put("name", cursor.getString(nameIndex) ?: "")
                        .put("number", cursor.getString(numberIndex) ?: "")
                )
            }
        }
        return if (array.length() == 0) "No matching contacts." else array.toString()
    }

    private fun readCallLog(context: Context, limit: Int): String {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            return "Reading the call log needs the phone permission. Grant it from the Tools tab."
        }
        val array = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && array.length() < limit) {
                array.put(
                    JSONObject()
                        .put("name", cursor.getString(nameIndex) ?: "")
                        .put("number", cursor.getString(numberIndex) ?: "")
                        .put("type", callTypeName(cursor.getInt(typeIndex)))
                        .put("date", cursor.getLong(dateIndex))
                        .put("duration_s", cursor.getLong(durationIndex))
                )
            }
        }
        return if (array.length() == 0) "The call log is empty." else array.toString()
    }

    private fun callTypeName(value: Int): String = when (value) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }

    private fun ipv4(value: Int): String = if (value == 0) "unavailable" else listOf(
        value and 0xff,
        value shr 8 and 0xff,
        value shr 16 and 0xff,
        value shr 24 and 0xff,
    ).joinToString(".")

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun tool(
        name: String,
        description: String,
        params: List<NeedleParam> = emptyList(),
        handler: suspend (JSONObject) -> String,
    ) = NeedleTool(name, description, params, handler)

    private fun stringParam(name: String, description: String, required: Boolean = true) =
        NeedleParam(name = name, type = "string", description = description, required = required)

    private fun intParam(name: String, description: String, required: Boolean = true) =
        NeedleParam(name = name, type = "integer", description = description, required = required)

    private fun boolParam(name: String, description: String) =
        NeedleParam(name = name, type = "boolean", description = description, required = true)

    private fun JSONObject.require(key: String): String {
        val value = optString(key).trim()
        if (value.isBlank()) throw IllegalArgumentException("The tool needs a value for '$key'.")
        return value
    }

    private fun JSONObject.requireInt(key: String): Int {
        if (!has(key) || isNull(key)) throw IllegalArgumentException("The tool needs a value for '$key'.")
        return optInt(key)
    }

    /** Text-to-speech needs one engine per process and a queue of in-flight utterances. */
    private object Speech {
        private var engine: TextToSpeech? = null
        private var ready = false
        private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

        suspend fun speak(context: Context, text: String): String {
            val tts = ensureEngine(context) ?: return "Text-to-speech is not available on this device."
            var waited = 0
            while (!ready && waited < 5_000) {
                delay(100)
                waited += 100
            }
            if (!ready) return "The text-to-speech engine did not start."
            val utteranceId = UUID.randomUUID().toString()
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            pending[utteranceId] = done
            val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(utteranceId)
                return "The text-to-speech engine refused the request."
            }
            val finished = withTimeoutOrNull(90_000) { done.await() }
            pending.remove(utteranceId)
            return if (finished == null) {
                "Text-to-speech timed out."
            } else {
                "Spoke aloud: $text"
            }
        }

        @Synchronized
        private fun ensureEngine(context: Context): TextToSpeech? {
            if (engine != null) return engine
            val created = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                engine?.language = Locale.getDefault()
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pending.remove(it)?.complete(Unit) }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pending.remove(it)?.complete(Unit) }
                    }
                })
            }
            engine = created
            return created
        }
    }
}
