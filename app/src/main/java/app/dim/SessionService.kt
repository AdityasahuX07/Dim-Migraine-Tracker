package app.dim

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that keeps the "Ongoing Migraine" notification alive while an episode is running.
 * The duration is always derived from the persisted startedAt; the service holds no timer state of its own.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as DimApp).repo
        val active = runBlocking(Dispatchers.IO) { repo.activeNow() }
        // Must go foreground right away (also on a sticky restart with a null intent).
        ServiceCompat.startForeground(this, OngoingNotification.ID,
            OngoingNotification.build(this, active?.startedAt ?: System.currentTimeMillis()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (active == null) { shutdown(); return START_NOT_STICKY }
        if (job == null) job = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            repo.active.collectLatest { cur ->
                if (cur == null) { shutdown(); return@collectLatest }
                while (true) {
                    val now = System.currentTimeMillis()
                    nm.notify(OngoingNotification.ID, OngoingNotification.build(this@SessionService, cur.startedAt, now))
                    // Wake once per minute, on the minute the app's own timer rolls over (hours + minutes only).
                    delay(60_000 - (now - cur.startedAt).mod(60_000L) + 50)
                }
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
