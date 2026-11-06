package ai.zoya.assistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * One place that answers "may I?" — for tools, services and the UI.
 * Tools call this before touching Intents; the onboarding screen calls it to
 * render its checklist. Same source of truth, so the two can never disagree.
 */
object PermissionState {

    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
    const val READ_CONTACTS = Manifest.permission.READ_CONTACTS
    const val CALL_PHONE = Manifest.permission.CALL_PHONE
    const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    data class Item(
        val permission: String,
        val title: String,
        val why: String,
        val unlocks: String,
        /** Required for the app to function at all vs. a nice-to-have. */
        val essential: Boolean,
        val minSdk: Int = Build.VERSION_CODES.O,
    )

    val ALL: List<Item> = listOf(
        Item(
            permission = RECORD_AUDIO,
            title = "Microphone",
            why = "So I can hear you. Shocking, I know.",
            unlocks = "Voice conversation, wake word",
            essential = true,
        ),
        Item(
            permission = POST_NOTIFICATIONS,
            title = "Notifications",
            why = "The little persistent card that keeps Android from killing me.",
            unlocks = "Background service, timers",
            essential = true,
            minSdk = Build.VERSION_CODES.TIRAMISU,
        ),
        Item(
            permission = READ_CONTACTS,
            title = "Contacts",
            why = "To find the right person when you say a name.",
            unlocks = "Calls and WhatsApp by name",
            essential = false,
        ),
        Item(
            permission = CALL_PHONE,
            title = "Phone",
            why = "So \"call mom\" actually dials instead of opening the dialler.",
            unlocks = "Placing calls directly",
            essential = false,
        ),
    )

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Permissions that still need asking, respecting API levels. */
    fun pending(context: Context): List<Item> = ALL.filter { item ->
        if (Build.VERSION.SDK_INT < item.minSdk) return@filter false
        !has(context, item.permission)
    }

    fun essentialsGranted(context: Context): Boolean =
        ALL.filter { it.essential }.all { item ->
            Build.VERSION.SDK_INT < item.minSdk || has(context, item.permission)
        }

    /** The exact manifest string list to hand to a permission launcher. */
    fun launcherPermissions(items: List<Item>): Array<String> = items.map { it.permission }.toTypedArray()

    fun labelFor(permission: String): String =
        ALL.firstOrNull { it.permission == permission }?.title ?: permission.substringAfterLast('.')
}
