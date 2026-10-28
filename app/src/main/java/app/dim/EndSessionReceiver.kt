package app.dim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** "End Session" notification button: finalizes the running episode without opening the app. */
class EndSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OngoingNotification.ACTION_END) return
        val app = context.applicationContext as DimApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repo.endActive()          // atomic + idempotent: repeated taps are no-ops
                OngoingNotification.stop(app) // only reached if the write succeeded
            } finally { pending.finish() }
        }
    }
}
