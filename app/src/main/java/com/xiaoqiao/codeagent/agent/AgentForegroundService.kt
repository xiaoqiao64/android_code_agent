package com.xiaoqiao.codeagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Minimal foreground service + wake lock so long agent runs are not killed.
 * targetSdk 28: no foregroundServiceType required.
 */
class AgentForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                val notification = Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Code Agent")
                    .setContentText("Agent is running…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIF_ID, notification)
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codeagent:agent").apply {
                        setReferenceCounted(false)
                        acquire(60 * 60 * 1000L)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent runs", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_runs"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.xiaoqiao.codeagent.STOP_AGENT_SERVICE"

        fun start(ctx: Context) {
            val i = Intent(ctx, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
