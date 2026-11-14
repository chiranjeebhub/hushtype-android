package app.hushtype.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.hushtype.HushtypeApp
import app.hushtype.R
import app.hushtype.ui.MainActivity

/**
 * A microphone foreground service that runs only while dictating and for two minutes after, so
 * Android lets Hushtype use the mic from the floating bubble. It is not kept running in between,
 * so there's no permanent notification or mic indicator.
 */
class MicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HushtypeApp.instance.dictation.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refused the microphone type. The accessibility service's own access to the mic
            // usually still works. A service started this way must still go foreground once, or
            // Android ends the app, so do that with a type that needs nothing, then stop.
            Log.w(TAG, "Couldn't start the microphone service", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HushtypeApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_hushtype)
            .setContentTitle(getString(R.string.notification_ready_title))
            .setContentText(getString(R.string.notification_ready_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "HushtypeMic"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.hushtype.STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MicService::class.java)) }
                .onFailure { Log.w(TAG, "Foreground service start refused", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MicService::class.java))
        }
    }
}
