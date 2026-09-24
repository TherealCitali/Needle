package dev.citali.needle.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The permissions the phone tools ask for, and a friendly name for each, so the
 * Tools tab can request exactly what a tool needs and explain what it unlocks.
 */
object DevicePermissions {

    data class Entry(
        val permission: String,
        val label: String,
        val why: String,
        val minSdk: Int = 0,
    )

    val all: List<Entry> = listOf(
        Entry(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Post status updates from the assistant", Build.VERSION_CODES.TIRAMISU),
        Entry(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "Read GPS position and Wi-Fi details", Build.VERSION_CODES.M),
        Entry(Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location", "Cheaper location fix when GPS is off", Build.VERSION_CODES.M),
        Entry(Manifest.permission.CAMERA, "Camera", "Take photos on request", Build.VERSION_CODES.M),
        Entry(Manifest.permission.RECORD_AUDIO, "Microphone", "Voice input for the chat box", Build.VERSION_CODES.M),
        Entry(Manifest.permission.SEND_SMS, "Send SMS", "Send text messages you dictate", Build.VERSION_CODES.M),
        Entry(Manifest.permission.READ_SMS, "Read SMS", "Read the recent inbox on request", Build.VERSION_CODES.M),
        Entry(Manifest.permission.CALL_PHONE, "Phone calls", "Place calls directly instead of opening the dialer", Build.VERSION_CODES.M),
        Entry(Manifest.permission.READ_CALL_LOG, "Call log", "Summarise recent calls", Build.VERSION_CODES.M),
        Entry(Manifest.permission.READ_CONTACTS, "Contacts", "Look up names and numbers", Build.VERSION_CODES.M),
        Entry(Manifest.permission.READ_PHONE_STATE, "Phone state", "Report carrier, SIM and network type", Build.VERSION_CODES.M),
    )

    /** Permissions this build can still ask for on this device. */
    fun requestable(): List<String> = all
        .filter { Build.VERSION.SDK_INT >= it.minSdk }
        .map { it.permission }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> = requestable().filterNot { granted(context, it) }

    fun label(permission: String): String =
        all.firstOrNull { it.permission == permission }?.label ?: permission.substringAfterLast('.')

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openWriteSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun canWriteSettings(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
}
