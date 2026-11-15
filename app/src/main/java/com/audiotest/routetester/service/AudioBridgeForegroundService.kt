package com.audiotest.routetester.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.audiotest.routetester.MainActivity
import com.audiotest.routetester.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * خدمة خلفية (Foreground Service) تؤدي وظيفة عملية حقيقية أثناء مكالمات واتساب:
 *
 * نظراً لأن نظام Android يربط المايك والسماعة في `setCommunicationDevice` أثناء مكالمات VoIP،
 * توفر هذه الخدمة وضع "الحارس الذكي للمكالمات (Call Mic Enforcer Daemon)":
 * - تراقب دخول الهاتف في وضع `MODE_IN_COMMUNICATION` (عند بدء مكالمة واتساب أو تيليجرام).
 * - تقوم تلقائياً بإجبار النظام على اختيار سماعة الجكة السلكية (Wired Headset) أو مايك الهاتف
 *   تجاوزاً لمايك البلوتوث الرديء، مع إشعار فوري بحالة المسار الفعلي.
 */
class AudioBridgeForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_WATCHDOG
        if (action == ACTION_STOP_WATCHDOG) {
            stopSelf()
            return START_NOT_STICKY
        }

        val preferWiredJack = intent?.getBooleanExtra(EXTRA_PREFER_WIRED_JACK, true) ?: true
        startForeground(NOTIFICATION_ID, buildNotification("حارس مسار الميكروفون نشط الآن..."))

        startCallRouteWatchdog(preferWiredJack)
        return START_STICKY
    }

    private fun startCallRouteWatchdog(preferWiredJack: Boolean) {
        watchdogJob?.cancel()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        watchdogJob = serviceScope.launch {
            while (isActive) {
                val mode = audioManager.mode
                if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val commDevices = audioManager.availableCommunicationDevices
                        val target = if (preferWiredJack) {
                            commDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                            } ?: commDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            }
                        } else {
                            commDevices.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                            }
                        }

                        val currentComm = audioManager.communicationDevice
                        if (target != null && currentComm?.id != target.id) {
                            try {
                                audioManager.setCommunicationDevice(target)
                            } catch (_: Exception) {}
                        }
                    }
                }
                delay(1200L)
            }
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        serviceScope.cancel()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioBridgeForegroundService::class.java).apply {
            action = ACTION_STOP_WATCHDOG
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(0, "إيقاف الحارس", pendingStop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "audio_route_tester_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_WATCHDOG = "com.audiotest.routetester.START_WATCHDOG"
        const val ACTION_STOP_WATCHDOG = "com.audiotest.routetester.STOP_WATCHDOG"
        const val EXTRA_PREFER_WIRED_JACK = "extra_prefer_wired_jack"
    }
}
