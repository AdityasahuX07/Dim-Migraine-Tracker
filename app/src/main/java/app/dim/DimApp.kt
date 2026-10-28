package app.dim

import android.app.Application
import app.dim.data.Repo
import app.dim.data.ai.AiInsightsManager
import app.dim.data.health.HealthConnectSource
import app.dim.data.health.HealthSync
import app.dim.data.health.RepoHealthStore
import app.dim.data.health.SharedHealthPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy

class DimApp : Application() {
    val repo by lazy { Repo(this) }

    /** Health Connect integration: read-only, and idle until the user connects it in Settings. */
    val health by lazy { HealthSync(HealthConnectSource(this), RepoHealthStore(repo), SharedHealthPrefs(this), CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    /** AI-Powered Migraine Insights using Gemini API. */
    val aiInsights by lazy { AiInsightsManager(this, repo, health) }

    override fun onCreate() {
        super.onCreate()
        OngoingNotification.ensureChannel(this)
        // Single source of truth: whenever an episode starts or ends (app, tile, shortcut, notification), the
        // notification/service follows the database.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            repo.active.distinctUntilChangedBy { it?.id }.collect { a ->
                if (a != null) OngoingNotification.start(this@DimApp, a.startedAt) else OngoingNotification.stop(this@DimApp)
            }
        }

        // Automatic refresh check when episodes are logged/updated
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repo.episodes.collect {
                aiInsights.checkAndRefreshIfNeeded()
            }
        }
    }
}
