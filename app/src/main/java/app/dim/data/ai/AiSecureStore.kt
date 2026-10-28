package app.dim.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure storage for the Gemini API key and cached analysis results.
 * The API key is encrypted using AES-256-GCM backed by the AndroidKeyStore,
 * ensuring keys never reside in plain text on the file system.
 */
class AiSecureStore(
    private val prefs: SharedPreferences,
    private val keyAlias: String = "DimGeminiKey"
) {
    constructor(
        context: Context,
        prefsName: String = "dim_ai_settings",
        keyAlias: String = "DimGeminiKey"
    ) : this(
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        keyAlias
    )

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private const val PREF_ENABLED = "ai_insights_enabled"
        private const val PREF_ENCRYPTED_KEY = "encrypted_gemini_key"
        private const val PREF_CACHED_ANALYSIS = "cached_analysis_json"
        private const val PREF_LAST_ANALYZED_HASH = "last_analyzed_hash"
        private const val PREF_LAST_ANALYZED_AT = "last_analyzed_at"
        private const val PREF_LAST_ERROR = "last_error_message"
        private const val PREF_SELECTED_MODEL = "selected_gemini_model"
    }

    /**
     * Obtains or generates the AES secret key in AndroidKeyStore (or fallback in unit tests).
     */
    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
            }
        } catch (_: Exception) {
            // Fallback for JVM unit tests where AndroidKeyStore is not present
            getFallbackJvmKey()
        }
    }

    private var jvmFallbackKey: SecretKey? = null
    private fun getFallbackJvmKey(): SecretKey {
        jvmFallbackKey?.let { return it }
        val kg = KeyGenerator.getInstance("AES")
        kg.init(KEY_SIZE)
        val k = kg.generateKey()
        jvmFallbackKey = k
        return k
    }

    /**
     * Encrypts plain text using AES-GCM and returns a Base64-encoded payload (IV + ciphertext).
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = getOrCreateSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts a Base64-encoded AES-GCM payload.
     */
    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.isEmpty()) return ""
            val ivLength = combined[0].toInt()
            val iv = ByteArray(ivLength)
            System.arraycopy(combined, 1, iv, 0, ivLength)
            val cipherLength = combined.size - 1 - ivLength
            val cipherText = ByteArray(cipherLength)
            System.arraycopy(combined, 1 + ivLength, cipherText, 0, cipherLength)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    // ───────────────────────── Preferences Access ─────────────────────────

    var isEnabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(PREF_ENABLED, value).apply()

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(PREF_ENCRYPTED_KEY).apply()
        } else {
            val enc = encrypt(trimmed)
            prefs.edit().putString(PREF_ENCRYPTED_KEY, enc).apply()
        }
    }

    fun getApiKey(): String {
        val enc = prefs.getString(PREF_ENCRYPTED_KEY, null) ?: return ""
        return decrypt(enc)
    }

    fun hasApiKey(): Boolean {
        val enc = prefs.getString(PREF_ENCRYPTED_KEY, null)
        return !enc.isNullOrBlank()
    }

    fun clearApiKey() {
        prefs.edit().remove(PREF_ENCRYPTED_KEY).apply()
    }

    fun saveAnalysis(analysis: AiAnalysis, dataHash: String) {
        val json = analysisToJson(analysis)
        prefs.edit()
            .putString(PREF_CACHED_ANALYSIS, json)
            .putString(PREF_LAST_ANALYZED_HASH, dataHash)
            .putLong(PREF_LAST_ANALYZED_AT, analysis.generatedAt)
            .remove(PREF_LAST_ERROR)
            .apply()
    }

    fun getCachedAnalysis(): AiAnalysis? {
        val json = prefs.getString(PREF_CACHED_ANALYSIS, null) ?: return null
        return try {
            jsonToAnalysis(json)
        } catch (_: Exception) {
            null
        }
    }

    fun getLastAnalyzedHash(): String? = prefs.getString(PREF_LAST_ANALYZED_HASH, null)

    fun getLastAnalyzedAt(): Long? {
        val t = prefs.getLong(PREF_LAST_ANALYZED_AT, 0L)
        return if (t > 0L) t else null
    }

    fun saveLastError(msg: String?) {
        if (msg == null) {
            prefs.edit().remove(PREF_LAST_ERROR).apply()
        } else {
            prefs.edit().putString(PREF_LAST_ERROR, msg).apply()
        }
    }

    fun getLastError(): String? = prefs.getString(PREF_LAST_ERROR, null)

    var selectedModel: String?
        get() = prefs.getString(PREF_SELECTED_MODEL, null)
        set(value) {
            if (value == null) prefs.edit().remove(PREF_SELECTED_MODEL).apply()
            else prefs.edit().putString(PREF_SELECTED_MODEL, value).apply()
        }

    fun clearAnalysis() {
        prefs.edit()
            .remove(PREF_CACHED_ANALYSIS)
            .remove(PREF_LAST_ANALYZED_HASH)
            .remove(PREF_LAST_ANALYZED_AT)
            .remove(PREF_LAST_ERROR)
            .apply()
    }

    // ───────────────────────── Serialization Helpers ─────────────────────────

    private fun analysisToJson(a: AiAnalysis): String {
        val obj = JSONObject()
        obj.put("headline", a.headline)
        obj.put("summary", a.summary)
        obj.put("detailedExplanation", a.detailedExplanation)
        obj.put("hasEnoughData", a.hasEnoughData)
        obj.put("generatedAt", a.generatedAt)
        obj.put("episodesAnalyzed", a.episodesAnalyzed)
        obj.put("healthDaysAnalyzed", a.healthDaysAnalyzed)
        obj.put("modelUsed", a.modelUsed)

        val tp = JSONArray()
        a.triggerPatterns.forEach { tp.put(it) }
        obj.put("triggerPatterns", tp)

        val pc = JSONArray()
        a.precautions.forEach { pc.put(it) }
        obj.put("precautions", pc)

        val wb = JSONArray()
        a.warningBulletPoints.forEach { wb.put(it) }
        obj.put("warningBulletPoints", wb)

        val rs = JSONArray()
        a.reliefSteps.forEach { rs.put(it) }
        obj.put("reliefSteps", rs)

        return obj.toString()
    }

    fun jsonToAnalysis(json: String): AiAnalysis {
        val obj = JSONObject(json)
        val tp = mutableListOf<String>()
        val tpArr = obj.optJSONArray("triggerPatterns")
        if (tpArr != null) {
            for (i in 0 until tpArr.length()) tp.add(tpArr.optString(i))
        }

        val pc = mutableListOf<String>()
        val pcArr = obj.optJSONArray("precautions")
        if (pcArr != null) {
            for (i in 0 until pcArr.length()) pc.add(pcArr.optString(i))
        }

        val wb = mutableListOf<String>()
        val wbArr = obj.optJSONArray("warningBulletPoints")
        if (wbArr != null) {
            for (i in 0 until wbArr.length()) wb.add(wbArr.optString(i))
        }

        val rs = mutableListOf<String>()
        val rsArr = obj.optJSONArray("reliefSteps")
        if (rsArr != null) {
            for (i in 0 until rsArr.length()) rs.add(rsArr.optString(i))
        }

        return AiAnalysis(
            headline = obj.optString("headline", ""),
            reliefSteps = rs,
            summary = obj.optString("summary", ""),
            detailedExplanation = obj.optString("detailedExplanation", ""),
            triggerPatterns = tp,
            precautions = pc,
            warningBulletPoints = wb,
            hasEnoughData = obj.optBoolean("hasEnoughData", true),
            generatedAt = obj.optLong("generatedAt", System.currentTimeMillis()),
            episodesAnalyzed = obj.optInt("episodesAnalyzed", 0),
            healthDaysAnalyzed = obj.optInt("healthDaysAnalyzed", 0),
            modelUsed = obj.optString("modelUsed", "")
        )
    }
}
