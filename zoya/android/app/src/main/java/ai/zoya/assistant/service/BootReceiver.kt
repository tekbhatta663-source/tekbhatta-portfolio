package ai.zoya.assistant.service

import ai.zoya.assistant.data.ZoyaPrefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the foreground service after a reboot or an app update.
 *
 * Opt-in on purpose: an assistant that silently starts listening every time the
 * phone boots is exactly the behaviour people uninstall apps over. The setting
 * defaults to off and the onboarding screen explains the trade-off.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = ZoyaPrefs(context)
        if (!prefs.autostartOnBoot) {
            Log.i("Zoya/Boot", "autostart disabled, staying quiet")
            return
        }

        Log.i("Zoya/Boot", "restarting Zoya after $action")
        val service = Intent(context, ZoyaService::class.java).setAction(ZoyaService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.startForegroundService(service) }
                .onFailure { Log.w("Zoya/Boot", "could not restart service", it) }
        } else {
            runCatching { context.startService(service) }
        }
    }
}
