package app.dim

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.dim.ui.elapsedText

/** Builds and manages the "Ongoing Migraine" notification. */
object OngoingNotification {
    const val CHANNEL_ID = "ongoing_migraine"
    const val ID = 1001
    const val ACTION_OPEN = "app.dim.OPEN_ONGOING"
    const val ACTION_END = "app.dim.END_SESSION"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ongoing migraine", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the running migraine with a button to end it"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        })
    }

    fun build(ctx: Context, startedAt: Long, now: Long = System.currentTimeMillis()): Notification {
        ensureChannel(ctx)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Tap: reuse the existing activity (onNewIntent) instead of stacking a new one.
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        // End Session: handled by a receiver, never opens the app.
        val end = PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, EndSessionReceiver::class.java).setAction(ACTION_END), flags)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ongoing)
            .setContentTitle("Ongoing Migraine")
            .setContentText(elapsedText(now - startedAt))
            .setContentIntent(open)
            .addAction(0, "End Session", end)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Starts the foreground service; if Android refuses (background start restrictions), falls back to a plain ongoing notification. */
    fun start(ctx: Context, startedAt: Long) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
        } catch (e: Exception) {
            try { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, startedAt)) } catch (_: Exception) {}
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, SessionService::class.java))
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
