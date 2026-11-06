package ai.zoya.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Explicit wake trigger, kept separate from the service so the detector (or an
 * external trigger such as a Bluetooth button, a shortcut, or a future MediaSession)
 * can wake her without holding a reference to the service.
 *
 * Being exported="false" in the manifest means only our own app can fire it.
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Zoya/Wake", "wake broadcast: ${intent.action}")
        ZoyaService.instance?.wakeUp(reason = "broadcast")
            ?: run {
                // Service is not running yet: start it, it will wake itself.
                val restart = Intent(context, ZoyaService::class.java)
                    .setAction(ZoyaService.ACTION_START)
                runCatching { context.startForegroundService(restart) }
            }
    }
}
