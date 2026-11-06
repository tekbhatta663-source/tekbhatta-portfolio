package ai.zoya.assistant

import ai.zoya.assistant.data.ZoyaPrefs
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class ZoyaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // One channel, created before anything can post to it, so the very
        // first startForeground() call on Android 13+ is never blocked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ai.zoya.assistant.service.ZoyaService.CHANNEL_ID,
                getString(R.string.notif_channel_listening),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_listening_desc)
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
        Log.i("Zoya/App", "boot (key configured: ${BuildConfig.GEMINI_API_KEY.isNotBlank()})")
        // Touch prefs once so the file exists before the service races for it.
        ZoyaPrefs(this)
    }
}
