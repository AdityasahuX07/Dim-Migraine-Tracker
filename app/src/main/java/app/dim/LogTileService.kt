package app.dim

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class LogTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onStartListening() = refresh()
    override fun onClick() {
        scope.launch { withContext(Dispatchers.IO) { (application as DimApp).repo.toggle() }; refresh() }
    }
    private fun refresh() {
        scope.launch {
            val active = withContext(Dispatchers.IO) { (application as DimApp).repo.activeNow() } != null
            qsTile?.apply { state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = if (active) "End migraine" else "Log migraine"; updateTile() }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
