package app.dim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dim.ui.*

/** Opened by Health Connect from its permission screen ("privacy policy"). Says what Dim reads and what it does with it. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DimTheme {
                Column(Modifier.fillMaxSize().background(Dim.bg).statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt("Health Connect", Type.title)
                    Card { Txt(HEALTH_PRIVACY_TEXT, Type.body) }
                    Press({ finish() }, Modifier.fillMaxWidth(), "Close") {
                        Txt("Close", Type.body.copy(color = Dim.accent), modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
    }
}
