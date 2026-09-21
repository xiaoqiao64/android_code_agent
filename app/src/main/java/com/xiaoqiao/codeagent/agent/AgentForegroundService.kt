package com.xiaoqiao.codeagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.xiaoqiao.codeagent.MainActivity
import com.xiaoqiao.codeagent.runtime.SshdServer

/**
 * Foreground service + wake lock. Reasons are refcounted so an agent run
 * finishing does not tear down a live sshd (and vice versa).
 * targetSdk 28: no foregroundServiceType required.
 */
class AgentForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent == null) {
            if (SshdServer.isWanted(this) && !SshdServer.isRunning()) {
                synchronized(reasons) { reasons.add(REASON_SSHD) }
                startForeground(NOTIF_ID, buildNotification())
                acquireWakeLock()
                SshdServer.start(this)
                return START_STICKY
            }
            if (synchronized(reasons) { reasons.isEmpty() }) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(NOTIF_ID, buildNotification())
            acquireWakeLock()
            return START_STICKY
        }
        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_AGENT
        when (intent.action) {
            ACTION_STOP -> {
                synchronized(reasons) { reasons.remove(reason) }
                startForeground(NOTIF_ID, buildNotification())
                if (synchronized(reasons) { reasons.isEmpty() }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_UPDATE -> {
                startForeground(NOTIF_ID, buildNotification())
            }
            else -> {
                synchronized(reasons) { reasons.add(reason) }
                startForeground(NOTIF_ID, buildNotification())
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        synchronized(reasons) { reasons.clear() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codeagent:keepalive").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val ssh = synchronized(reasons) { REASON_SSHD in reasons }
        val agent = synchronized(reasons) { REASON_AGENT in reasons }
        val text = when {
            ssh && agent -> "Agent + SSH ${SshdServer.info?.command() ?: "running"}"
            ssh -> SshdServer.info?.command() ?: "SSH running"
            else -> "Agent is running…"
        }
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Code Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(launch)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Keep alive", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_runs"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.xiaoqiao.codeagent.STOP_AGENT_SERVICE"
        const val ACTION_UPDATE = "com.xiaoqiao.codeagent.UPDATE_AGENT_SERVICE"
        const val EXTRA_REASON = "reason"
        const val REASON_AGENT = "agent"
        const val REASON_SSHD = "sshd"

        private val reasons = mutableSetOf<String>()

        fun start(ctx: Context, reason: String = REASON_AGENT) {
            val app = ctx.applicationContext
            val i = Intent(app, AgentForegroundService::class.java).putExtra(EXTRA_REASON, reason)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }

        fun stop(ctx: Context, reason: String = REASON_AGENT) {
            val app = ctx.applicationContext
            val i = Intent(app, AgentForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REASON, reason)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }

        fun update(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, AgentForegroundService::class.java).setAction(ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }
    }
}
