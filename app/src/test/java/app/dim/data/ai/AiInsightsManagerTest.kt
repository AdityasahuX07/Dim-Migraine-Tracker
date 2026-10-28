package app.dim.data.ai

import android.content.SharedPreferences
import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.health.HealthDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiInsightsManagerTest {

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) removals.add(key) else pending[key] = value
            return this
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
        override fun clear(): SharedPreferences.Editor { map.clear(); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removals.forEach { map.remove(it) }
            removals.clear()
            map.putAll(pending)
            pending.clear()
        }
    }

    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private lateinit var fakePrefs: FakePrefs
    private lateinit var store: AiSecureStore

    private var episodesList = mutableListOf<Episode>()
    private var healthDaysList = mutableListOf<HealthDay>()
    private var currentProfile = Profile(diagnosis = "Episodic")

    private fun createManager(gemini: GeminiClient = GeminiClient()): AiInsightsManager {
        val dispatcher = Dispatchers.Unconfined
        return AiInsightsManager(
            store = store,
            geminiClient = gemini,
            scope = CoroutineScope(dispatcher),
            ioDispatcher = dispatcher,
            episodeProvider = { episodesList },
            healthDayProvider = { healthDaysList },
            profileProvider = { currentProfile }
        )
    }

    @Before
    fun setUp() {
        fakePrefs = FakePrefs()
        store = AiSecureStore(fakePrefs, keyAlias = "ManagerTestKey")
        episodesList.clear()
        healthDaysList.clear()
        currentProfile = Profile(diagnosis = "Episodic")
    }

    @Test
    fun `initial state is DISABLED when not enabled in storage`() {
        val manager = createManager()
        assertEquals(AiStatus.DISABLED, manager.state.value.status)
        assertFalse(manager.state.value.enabled)
        assertFalse(manager.state.value.hasKey)
    }

    @Test
    fun `enabling with no key transitions to NO_KEY`() {
        val manager = createManager()
        manager.setEnabled(true)
        assertEquals(AiStatus.NO_KEY, manager.state.value.status)
        assertTrue(manager.state.value.enabled)
        assertFalse(manager.state.value.hasKey)
    }

    @Test
    fun `saving api key with fewer than 2 finished episodes transitions to INSUFFICIENT_DATA safely`() = runBlocking {
        var callCount = 0
        val testGemini = object : GeminiClient() {
            override suspend fun generateAnalysis(
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int,
                preferredModel: String?,
                onModelChosen: ((String) -> Unit)?
            ): AiAnalysis {
                callCount++
                throw RuntimeException("Should not be called")
            }
        }

        val manager = createManager(testGemini)
        manager.setEnabled(true)
        episodesList.add(Episode(id = 1, startedAt = 1000L, endedAt = 2000L)) // Only 1 episode

        manager.saveApiKey("AIzaSyValidKey")

        assertEquals(AiStatus.INSUFFICIENT_DATA, manager.state.value.status)
        assertNotNull(manager.state.value.analysis)
        assertFalse(manager.state.value.analysis!!.hasEnoughData)
        assertEquals(0, callCount)
    }

    @Test
    fun `saving api key with 2 or more finished episodes generates analysis and transitions to READY`() = runBlocking {
        episodesList.add(Episode(id = 1, startedAt = 1000L, endedAt = 2000L))
        episodesList.add(Episode(id = 2, startedAt = 3000L, endedAt = 4000L))

        val testGemini = object : GeminiClient() {
            override suspend fun generateAnalysis(
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int,
                preferredModel: String?,
                onModelChosen: ((String) -> Unit)?
            ): AiAnalysis {
                onModelChosen?.invoke("gemini-2.0-flash")
                return AiAnalysis(
                    summary = "Strong correlation with late nights.",
                    detailedExplanation = "Sleep was under 6h.",
                    triggerPatterns = listOf("Sleep deficit"),
                    precautions = listOf("Regular sleep schedule"),
                    warningBulletPoints = listOf("Neck tension"),
                    hasEnoughData = true,
                    generatedAt = 9999L,
                    episodesAnalyzed = episodesAnalyzed,
                    healthDaysAnalyzed = healthDaysAnalyzed,
                    modelUsed = "gemini-2.0-flash"
                )
            }
        }

        val testManager = createManager(testGemini)
        testManager.setEnabled(true)
        testManager.saveApiKey("AIzaSyValidKey")

        assertEquals(AiStatus.READY, testManager.state.value.status)
        val analysis = testManager.state.value.analysis
        assertNotNull(analysis)
        assertEquals("Strong correlation with late nights.", analysis!!.summary)
        assertEquals(2, analysis.episodesAnalyzed)
        assertEquals("gemini-2.0-flash", testManager.state.value.selectedModel)
    }

    @Test
    fun `gemini error transitions to ERROR state with friendly message`() = runBlocking {
        val errorGemini = object : GeminiClient() {
            override suspend fun generateAnalysis(
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int,
                preferredModel: String?,
                onModelChosen: ((String) -> Unit)?
            ): AiAnalysis {
                throw GeminiException("Invalid Gemini API key. Please verify your key in Settings.", 403)
            }
        }

        episodesList.add(Episode(id = 1, startedAt = 1000L, endedAt = 2000L))
        episodesList.add(Episode(id = 2, startedAt = 3000L, endedAt = 4000L))

        val manager = createManager(errorGemini)
        manager.setEnabled(true)
        manager.saveApiKey("AIzaSyInvalidKey")

        assertEquals(AiStatus.ERROR, manager.state.value.status)
        assertEquals("Invalid Gemini API key. Please verify your key in Settings.", manager.state.value.errorMessage)
    }

    @Test
    fun `clearAnalysis removes cached insights and resets status`() = runBlocking {
        val testGemini = object : GeminiClient() {
            override suspend fun generateAnalysis(
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int,
                preferredModel: String?,
                onModelChosen: ((String) -> Unit)?
            ): AiAnalysis {
                return AiAnalysis(
                    summary = "Test summary",
                    detailedExplanation = "",
                    triggerPatterns = emptyList(),
                    precautions = emptyList(),
                    warningBulletPoints = emptyList(),
                    hasEnoughData = true,
                    generatedAt = 1000L,
                    episodesAnalyzed = 2,
                    modelUsed = "gemini-2.0-flash"
                )
            }
        }

        episodesList.add(Episode(id = 1, startedAt = 1000L, endedAt = 2000L))
        episodesList.add(Episode(id = 2, startedAt = 3000L, endedAt = 4000L))

        val manager = createManager(testGemini)
        manager.setEnabled(true)
        manager.saveApiKey("ValidKey")

        assertEquals(AiStatus.READY, manager.state.value.status)
        assertNotNull(manager.state.value.analysis)

        manager.clearAnalysis()
        assertNull(manager.state.value.analysis)
        assertEquals(AiStatus.INSUFFICIENT_DATA, manager.state.value.status)
    }
}
