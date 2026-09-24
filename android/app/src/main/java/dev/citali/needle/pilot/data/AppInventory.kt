package dev.citali.needle.pilot.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

/**
 * A real installed app, as reported by PackageManager.
 *
 * [enabled] is the package's own enabled setting. A disabled app is still
 * installed and still resolvable, but cannot be launched until it is re-enabled,
 * which is a distinction the agent has to be able to see and explain.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val system: Boolean,
    val launchable: Boolean,
) {
    val state: String
        get() = when {
            !enabled -> "disabled"
            !launchable -> "no launcher entry"
            else -> "enabled"
        }
}

/**
 * The device's installed-app list.
 *
 * This exists because the agent previously had no ground truth about what is
 * installed. Asked to open an app it did not know, a model would guess a package
 * name from context -- producing plausible-looking but wrong ids such as
 * "dev.citali.brevent", derived from TaskPilot's own application id. Resolution
 * is now done against PackageManager, and a name that cannot be resolved is
 * reported as not installed rather than guessed at.
 */
object AppInventory {

    @Volatile
    private var cache: List<InstalledApp>? = null

    /** All installed apps that the platform will let us see. */
    fun all(context: Context, refresh: Boolean = false): List<InstalledApp> {
        cache?.takeIf { !refresh }?.let { return it }
        val pm = context.packageManager

        val launchable: Set<String> = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())

        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val apps = installed.mapNotNull { info ->
            runCatching {
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    enabled = info.enabled,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    launchable = info.packageName in launchable ||
                        pm.getLaunchIntentForPackage(info.packageName) != null,
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase(Locale.ROOT) }

        // Fall back to the launcher-only view if the full query was blocked.
        val result = apps.ifEmpty {
            launchable.mapNotNull { pkg ->
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    InstalledApp(pkg, pm.getApplicationLabel(info).toString(), info.enabled, false, true)
                }.getOrNull()
            }.sortedBy { it.label.lowercase(Locale.ROOT) }
        }

        cache = result
        return result
    }

    /** Apps a user would recognise from their launcher, plus disabled ones. */
    fun userVisible(context: Context, refresh: Boolean = false): List<InstalledApp> =
        all(context, refresh).filter { it.launchable || !it.enabled }
            .filter { it.packageName != context.packageName }

    /**
     * Resolves a spoken app name to an installed package.
     *
     * Matching is deliberately strict-to-loose: exact package, exact label,
     * label ignoring spaces, prefix, then word-boundary containment. Anything
     * looser risks the same class of bug this class was written to remove.
     */
    fun resolve(context: Context, query: String, refresh: Boolean = false): InstalledApp? {
        val q = query.trim()
        if (q.isBlank()) return null
        val apps = all(context, refresh)
        val ql = q.lowercase(Locale.ROOT)
        val qCompact = ql.replace(" ", "")

        fun List<InstalledApp>.preferLaunchable(): InstalledApp? =
            firstOrNull { it.launchable && it.enabled } ?: firstOrNull { it.launchable } ?: firstOrNull()

        apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it }
        apps.filter { it.label.equals(q, ignoreCase = true) }.preferLaunchable()?.let { return it }
        apps.filter { it.label.lowercase(Locale.ROOT).replace(" ", "") == qCompact }
            .preferLaunchable()?.let { return it }
        apps.filter { it.label.lowercase(Locale.ROOT).startsWith(ql) }
            .preferLaunchable()?.let { return it }
        apps.filter {
            Regex("\\b" + Regex.escape(ql)).containsMatchIn(it.label.lowercase(Locale.ROOT))
        }.preferLaunchable()?.let { return it }
        // Last resort: the package id itself contains the word (e.g. "brevent"
        // matching me.piebridge.brevent).
        return apps.filter { it.packageName.lowercase(Locale.ROOT).contains(ql) }.preferLaunchable()
    }

    /**
     * Compact listing for the model prompt: label, package, and state.
     * Capped so a device with hundreds of apps cannot crowd out the UI tree.
     */
    fun promptListing(context: Context, limit: Int = 60): String {
        val apps = userVisible(context)
        if (apps.isEmpty()) return "(app list unavailable)"
        val shown = apps.take(limit)
        return buildString {
            shown.forEach { app ->
                append("- ").append(app.label)
                append(" [").append(app.packageName).append(']')
                if (!app.enabled) append(" (disabled)")
                append('\n')
            }
            if (apps.size > shown.size) {
                append("… and ").append(apps.size - shown.size).append(" more\n")
            }
        }.trimEnd()
    }

    fun invalidate() {
        cache = null
    }
}
