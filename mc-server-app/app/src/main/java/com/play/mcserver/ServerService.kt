package com.play.mcserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/** 화면을 꺼도 서버가 계속 돌도록 붙잡아 두는 포그라운드 서비스. */
class ServerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null
    private val listener: (ServerState) -> Unit = { s ->
        if (s == ServerState.STOPPED) stopSelf() else updateNotification(s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "마크 서버", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification(ServerManager.state))

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcserver:server").apply { acquire() }
        // 와이파이 절전 모드를 끔. 절전 모드에서는 패킷이 100~300ms씩 묶여서 핑이 튐
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mcserver:wifi").apply {
            setReferenceCounted(false); acquire()
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 안드로이드 10+ : 저지연 모드 (앱 화면이 켜져 있을 때 가장 효과적)
            lowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "mcserver:lowlatency").apply {
                setReferenceCounted(false); acquire()
            }
        }

        ServerManager.addStateListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ServerManager.state == ServerState.STOPPED) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServerManager.removeStateListener(listener)
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        lowLatencyLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun updateNotification(s: ServerState) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: ServerState): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("마크 서버")
            .setContentText(s.label.removePrefix("● "))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1
    }
}
