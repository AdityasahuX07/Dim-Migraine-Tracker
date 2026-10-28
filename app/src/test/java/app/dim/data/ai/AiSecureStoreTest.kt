package app.dim.data.ai

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiSecureStoreTest {

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

    private lateinit var prefs: FakePrefs
    private lateinit var store: AiSecureStore

    @Before
    fun setUp() {
        prefs = FakePrefs()
        store = AiSecureStore(prefs, keyAlias = "TestAiKey")
    }

    @Test
    fun `encryption and decryption preserves plain text`() {
        val originalKey = "AIzaSyD-TestKey_1234567890abcdef"
        val encrypted = store.encrypt(originalKey)

        assertFalse(encrypted == originalKey)
        assertTrue(encrypted.isNotBlank())

        val decrypted = store.decrypt(encrypted)
        assertEquals(originalKey, decrypted)
    }

    @Test
    fun `save and get api key works and does not store plaintext`() {
        val apiKey = "AIzaSySecretApiKeyForGemini"
        store.saveApiKey(apiKey)

        assertTrue(store.hasApiKey())
        assertEquals(apiKey, store.getApiKey())

        // Ensure raw string in prefs is encrypted, not plain text
        val rawPref = prefs.getString("encrypted_gemini_key", null)
        assertNotNull(rawPref)
        assertFalse(rawPref!!.contains("AIzaSy"))
    }

    @Test
    fun `clearApiKey removes key from storage`() {
        store.saveApiKey("SomeKey")
        assertTrue(store.hasApiKey())

        store.clearApiKey()
        assertFalse(store.hasApiKey())
        assertEquals("", store.getApiKey())
    }

    @Test
    fun `analysis serialization and deserialization round trips all fields`() {
        val analysis = AiAnalysis(
            summary = "Sample summary.",
            detailedExplanation = "Sample detailed explanation.",
            triggerPatterns = listOf("Trigger A", "Trigger B"),
            precautions = listOf("Precaution 1", "Precaution 2"),
            warningBulletPoints = listOf("Warning 1"),
            hasEnoughData = true,
            generatedAt = 1700000000000L,
            episodesAnalyzed = 4,
            healthDaysAnalyzed = 10
        )

        store.saveAnalysis(analysis, "sample_hash_123")

        val cached = store.getCachedAnalysis()
        assertNotNull(cached)
        assertEquals("Sample summary.", cached!!.summary)
        assertEquals("Sample detailed explanation.", cached.detailedExplanation)
        assertEquals(listOf("Trigger A", "Trigger B"), cached.triggerPatterns)
        assertEquals(listOf("Precaution 1", "Precaution 2"), cached.precautions)
        assertEquals(listOf("Warning 1"), cached.warningBulletPoints)
        assertTrue(cached.hasEnoughData)
        assertEquals(1700000000000L, cached.generatedAt)
        assertEquals(4, cached.episodesAnalyzed)
        assertEquals(10, cached.healthDaysAnalyzed)

        assertEquals("sample_hash_123", store.getLastAnalyzedHash())
        assertEquals(1700000000000L, store.getLastAnalyzedAt())
    }

    @Test
    fun `selectedModel property getter and setter works`() {
        assertNull(store.selectedModel)
        store.selectedModel = "gemini-2.0-flash"
        assertEquals("gemini-2.0-flash", store.selectedModel)
        store.selectedModel = null
        assertNull(store.selectedModel)
    }

    @Test
    fun `clearAnalysis removes cached analysis and hash`() {
        val analysis = AiAnalysis(
            summary = "Summary",
            detailedExplanation = "",
            triggerPatterns = emptyList(),
            precautions = emptyList(),
            warningBulletPoints = emptyList(),
            hasEnoughData = true,
            generatedAt = 1000L,
            episodesAnalyzed = 2
        )
        store.saveAnalysis(analysis, "hash")
        assertNotNull(store.getCachedAnalysis())

        store.clearAnalysis()
        assertNull(store.getCachedAnalysis())
        assertNull(store.getLastAnalyzedHash())
        assertNull(store.getLastAnalyzedAt())
    }
}
