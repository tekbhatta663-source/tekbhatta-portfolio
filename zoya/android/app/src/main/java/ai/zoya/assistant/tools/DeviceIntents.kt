package ai.zoya.assistant.tools

import ai.zoya.assistant.R
import ai.zoya.assistant.data.ZoyaPrefs
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * DeviceIntents
 * -------------
 * All the Android plumbing that actually makes things happen: launching apps,
 * dialling, WhatsApp deep links, email composition, opening URLs.
 *
 * Two platform realities this class deals with head-on:
 *
 *  1. **Background activity starts are restricted (Android 10+).** Zoya usually
 *     acts while the app is backgrounded as a foreground service. A plain
 *     startActivity() will be silently dropped. We always try, and on failure
 *     post a high-priority notification whose content intent opens the thing —
 *     then tell the model what really happened so she can say so.
 *
 *  2. **Some actions need an Activity, some can be a service/notification.**
 *     Opening a URL or composing an email genuinely needs the user's screen.
 */
class DeviceIntents(
    private val context: Context,
    private val prefs: ZoyaPrefs,
) {
    companion object {
        private const val TAG = "Zoya/Intents"

        /** Curated aliases for the apps people actually name out loud. */
        private val KNOWN_APPS: Map<String, String> = mapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "calculator" to "com.google.android.calculator",
            "chrome" to "com.android.chrome",
            "camera" to "com.google.android.GoogleCamera",
            "spotify" to "com.spotify.music",
            "facebook" to "com.facebook.katana",
            "tiktok" to "com.zhiliaoapp.musically",
            "telegram" to "org.telegram.messenger",
            "netflix" to "com.netflix.mediaclient",
            "play store" to "com.android.vending",
            "settings" to "com.android.settings",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "photos" to "com.google.android.apps.photos",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "linkedin" to "com.linkedin.android",
            "snapchat" to "com.snapchat.android",
            "zoom" to "us.zoom.videomeetings",
            "drive" to "com.google.android.apps.docs",
            "files" to "com.google.android.documentsui",
        )

        private val NOTIF_ID = 4101
    }

    data class AppTarget(val packageName: String, val label: String)

    // ------------------------------------------------------------ resolution --

    /**
     * Resolve a spoken app name to an installed package. Order:
     *   1. exact package name
     *   2. curated alias
     *   3. launcher label match (needs <queries> in the manifest; we have it)
     */
    fun resolveApp(raw: String): AppTarget? {
        val needle = raw.trim().lowercase()
            .removeSuffix(" app")
            .removePrefix("the ")
            .trim()
        if (needle.isEmpty()) return null

        // 1. package id given directly
        if (needle.contains('.')) {
            return runCatching {
                val info = context.packageManager.getApplicationInfo(raw.trim(), 0)
                AppTarget(raw.trim(), context.packageManager.getApplicationLabel(info).toString())
            }.getOrNull()
        }

        val pm = context.packageManager

        // 2. curated alias
        KNOWN_APPS[needle]?.let { pkg ->
            if (isInstalled(pkg)) return AppTarget(pkg, prettyLabel(pkg))
        }

        // 3. launcher apps, fuzzy on the label
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        val candidates = apps.mapNotNull { info ->
            val label = info.loadLabel(pm).toString()
            val lower = label.lowercase()
            val score = when {
                lower == needle -> 100
                lower.startsWith(needle) -> 80
                lower.contains(needle) -> 60
                needle.contains(lower) -> 40
                else -> 0
            }
            if (score > 0) Triple(info.activityInfo.packageName, label, score) else null
        }
        return candidates.maxByOrNull { it.third }?.let { AppTarget(it.first, it.second) }
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun prettyLabel(pkg: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    // --------------------------------------------------------------- actions --

    fun launchApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        return fire(intent, "android.intent.action.MAIN", fallbackNotification = "Open $pkg")
    }

    /** Place a call (ACTION_CALL) or fall back to the dialler (ACTION_DIAL). */
    fun dial(number: String, directCall: Boolean): Boolean {
        val uri = Uri.parse("tel:${Uri.encode(number)}")
        if (directCall) {
            val call = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fire(call, Intent.ACTION_CALL, fallbackNotification = "Call $number")) return true
            // Permission was granted but the OEM blocked it: let the user press go.
        }
        val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(dial, Intent.ACTION_DIAL, fallbackNotification = "Call $number")
    }

    fun hasWhatsApp(): Boolean =
        isInstalled("com.whatsapp") || isInstalled("com.whatsapp.w4b")

    /**
     * WhatsApp deep link. WhatsApp has no public "send" API, so the most we can
     * honestly do is open the chat with the text typed in. `wa.me` resolves
     * every number, including ones saved in local format.
     */
    fun openWhatsApp(numbers: List<String>, message: String): Boolean {
        val encoded = Uri.encode(message)
        for (raw in numbers) {
            val digits = raw.filter { it.isDigit() || it == '+' }.removePrefix("+")
            if (digits.length < 7) continue
            val url = "https://wa.me/$digits" + if (message.isNotBlank()) "?text=$encoded" else ""
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fire(intent, Intent.ACTION_VIEW, fallbackNotification = "WhatsApp")) return true
        }
        // No usable number, or no WhatsApp handler: open the picker.
        if (message.isNotBlank()) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fire(send, Intent.ACTION_SEND, fallbackNotification = "Share message")) return true
        }
        return false
    }

    fun composeEmail(to: String, subject: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(to)}")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fire(intent, Intent.ACTION_SENDTO, fallbackNotification = "Email $to")) return true
        // Some devices only answer a general SEND intent.
        val fallback = Intent(Intent.ACTION_SEND)
            .setType("message/rfc822")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fire(fallback, Intent.ACTION_SEND, fallbackNotification = "Email $to")
    }

    fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Prefer an in-app browser so Zoya stays in the foreground and can keep
        // talking about whatever the user is looking at.
        val custom = prefs.inAppBrowserPackage
        if (custom.isNotBlank() && isInstalled(custom)) intent.setPackage(custom)
        return fire(intent, Intent.ACTION_VIEW, fallbackNotification = UrlTools.prettyHost(url))
    }

    // ------------------------------------------------------------- internals --

    /**
     * Try to start an activity. Returns false (never throws) when the platform
     * blocks it, in which case we leave the user a notification they can tap.
     */
    private fun fire(intent: Intent, action: String, fallbackNotification: String): Boolean {
        return try {
            if (action != Intent.ACTION_SEND &&
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null
            ) {
                Log.w(TAG, "nothing handles $action ${intent.data}")
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "background activity start blocked; notifying instead", e)
            notifyFallback(intent, fallbackNotification)
            false
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
            notifyFallback(intent, fallbackNotification)
            false
        }
    }

    /** Tap-to-continue card, the only reliable way out of a blocked start. */
    private fun notifyFallback(intent: Intent, title: String) {
        if (!PermissionState.has(context, PermissionState.POST_NOTIFICATIONS)) return
        val pending = android.app.PendingIntent.getActivity(
            context,
            intent.hashCode(),
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ai.zoya.assistant.service.ZoyaService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_zoya_notification)
            .setContentTitle(title)
            .setContentText("Android blocked the background launch. Tap to continue.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID + intent.hashCode(), notification)
        }
    }
}
