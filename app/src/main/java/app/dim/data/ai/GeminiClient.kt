package app.dim.data.ai

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Communicates with the Google Gemini REST API (v1beta) to generate insights.
 * Automatically chooses the best model with high performance:
 * - Directly targets the fastest active model (defaulting to `gemini-2.0-flash`).
 * - Self-heals through candidate models and ModelService.ListModels if a model is unavailable or overloaded.
 * - Retries transient spikes (503/429/500) and smoothly falls back to alternative models.
 * - Enforces generous timeouts and compact generation limits to prevent timeout errors.
 */
open class GeminiClient(
    private val connectTimeoutMs: Int = 30000,
    private val readTimeoutMs: Int = 60000
) {
    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_PRIMARY_MODEL = "gemini-2.0-flash"
        val FALLBACK_CANDIDATE_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite-preview-02-05",
            "gemini-2.0-flash-exp",
            "gemini-2.0-flash-001",
            "gemini-2.0-pro-exp-02-05",
            "gemini-1.5-flash-latest",
            "gemini-1.5-flash-002",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
            "gemini-1.5-pro-latest",
            "gemini-1.5-pro-002",
            "gemini-1.5-pro"
        )
    }

    /**
     * Queries the Gemini API ModelService.ListModels endpoint to fetch all active models
     * that support `generateContent` for the given API key.
     */
    open fun listAvailableModels(apiKey: String): List<String> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) return emptyList()

        val endpoint = "$BASE_URL/models?key=$trimmedKey"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = connection.responseCode
            if (code in 200..299) {
                val text = readStream(connection.inputStream)
                parseModelsList(text)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses the response from ModelService.ListModels.
     */
    fun parseModelsList(jsonText: String): List<String> {
        val root = JSONObject(jsonText)
        val modelsArr = root.optJSONArray("models") ?: return emptyList()
        val result = mutableListOf<String>()

        for (i in 0 until modelsArr.length()) {
            val modelObj = modelsArr.optJSONObject(i) ?: continue
            val rawName = modelObj.optString("name", "")
            val methods = modelObj.optJSONArray("supportedGenerationMethods")

            val supportsGenerateContent = if (methods != null) {
                var found = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") {
                        found = true
                        break
                    }
                }
                found
            } else false

            if (supportsGenerateContent && rawName.contains("gemini", ignoreCase = true)) {
                result.add(rawName)
            }
        }

        return result
    }

    /**
     * Scores available models to pick the best fast, reliable model for structured generation.
     * Prefers newer versions and flash / flash-lite models.
     */
    open fun scoreModel(name: String): Double {
        val lower = name.lowercase().removePrefix("models/")
        if (!lower.contains("gemini")) return -100.0

        var score = 0.0

        // Extract version number like 2.0, 1.5, 1.0
        val versionMatch = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(lower)
        val version = versionMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        score += version * 100.0

        // Prefer flash for speed, lower latency, and generous free quotas
        if (lower.contains("flash")) {
            score += 80.0
            if (lower.contains("lite")) score += 15.0 // Prioritize flash-lite high-throughput variant
            if (lower.contains("8b")) score -= 5.0
        } else if (lower.contains("pro")) {
            score += 40.0
        }

        // Slight penalty for experimental / preview builds compared to standard releases
        if (lower.contains("exp") || lower.contains("preview")) {
            score -= 5.0
        }

        // Avoid embedding, audio-only, or specialized models
        if (lower.contains("vision") || lower.contains("embedding") || lower.contains("tts") || lower.contains("audio")) {
            score -= 200.0
        }

        return score
    }

    open fun selectBestModel(availableModels: List<String>): String {
        if (availableModels.isEmpty()) return DEFAULT_PRIMARY_MODEL
        return availableModels.maxByOrNull { scoreModel(it) } ?: availableModels.first()
    }

    /**
     * Executes the generation request with automatic model resolution.
     * 1. Fast path: Tries [preferredModel] or [DEFAULT_PRIMARY_MODEL] directly.
     * 2. Overload / Unavailability recovery: If the primary model returns 503 (high demand) or 404,
     *    it dynamically queries `listAvailableModels` to discover all active, compatible models
     *    on the user's API key and sequentially tries each one until insights are generated.
     */
    open suspend fun generateAnalysis(
        apiKey: String,
        prompt: String,
        episodesAnalyzed: Int,
        healthDaysAnalyzed: Int,
        preferredModel: String? = null,
        onModelChosen: ((String) -> Unit)? = null
    ): AiAnalysis {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw GeminiException("No Gemini API key provided. Please configure it in Settings.")
        }

        val attemptedModels = mutableSetOf<String>()
        var lastException: GeminiException? = null

        // 1. Fast path: Attempt primary target directly
        val primaryTarget = (preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_PRIMARY_MODEL).removePrefix("models/")
        attemptedModels.add(primaryTarget)
        try {
            val result = executeSingleGenerateWithRetry(primaryTarget, trimmedKey, prompt, episodesAnalyzed, healthDaysAnalyzed)
            onModelChosen?.invoke(primaryTarget)
            return result
        } catch (e: GeminiException) {
            lastException = e
            // For client authentication errors (400, 401, 403), abort immediately without testing other models
            if (!isFallbackWarranted(e.statusCode, e.message)) {
                throw e
            }
        }

        // 2. Query live available models from Google AI Studio catalog for this key
        val liveModels = try {
            listAvailableModels(trimmedKey)
        } catch (_: Exception) {
            emptyList()
        }

        // 3. Assemble candidate models list: live models scored from best to lowest, then known fallbacks
        val candidates = buildList {
            if (liveModels.isNotEmpty()) {
                val sortedLive = liveModels.map { it.removePrefix("models/") }
                    .sortedByDescending { scoreModel(it) }
                for (m in sortedLive) {
                    if (!contains(m)) add(m)
                }
            }
            for (fallback in FALLBACK_CANDIDATE_MODELS) {
                if (!contains(fallback)) add(fallback)
            }
        }.filter { !attemptedModels.contains(it) }

        // 4. Try each discovered candidate until one succeeds
        for (candidate in candidates) {
            attemptedModels.add(candidate)
            try {
                val result = executeSingleGenerateWithRetry(candidate, trimmedKey, prompt, episodesAnalyzed, healthDaysAnalyzed)
                onModelChosen?.invoke(candidate)
                return result
            } catch (e: GeminiException) {
                lastException = e
                if (!isFallbackWarranted(e.statusCode, e.message)) {
                    throw e
                }
            }
        }

        throw lastException ?: GeminiException("Gemini service is temporarily unavailable. Please try again shortly.", 503)
    }

    /**
     * Executes single content generation with automatic retry on transient server errors (503, 429, 500, 502, 504).
     */
    open suspend fun executeSingleGenerateWithRetry(
        modelName: String,
        apiKey: String,
        prompt: String,
        episodesAnalyzed: Int,
        healthDaysAnalyzed: Int
    ): AiAnalysis {
        var lastErr: GeminiException? = null
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            try {
                return executeSingleGenerate(modelName, apiKey, prompt, episodesAnalyzed, healthDaysAnalyzed)
            } catch (e: GeminiException) {
                lastErr = e
                val code = e.statusCode
                val isTransient = code == 503 || code == 429 || code == 500 || code == 502 || code == 504
                if (!isTransient || attempt == maxAttempts) {
                    throw e
                }
                delay(1000L * attempt)
            }
        }
        throw lastErr ?: GeminiException("Failed to generate with model $modelName")
    }

    open fun isFallbackWarranted(statusCode: Int?, message: String?): Boolean {
        if (statusCode == null) return true // Network timeout or socket error
        if (statusCode == 400 || statusCode == 401 || statusCode == 403) return false
        return statusCode == 404 || statusCode == 503 || statusCode == 429 || statusCode in 500..504 || isModelNotFoundError(message)
    }

    open fun isModelNotFoundError(msg: String?): Boolean {
        if (msg == null) return false
        val lower = msg.lowercase()
        return lower.contains("not found") || lower.contains("not supported for generatecontent") || lower.contains("404")
    }

    open fun executeSingleGenerate(
        modelName: String,
        apiKey: String,
        prompt: String,
        episodesAnalyzed: Int,
        healthDaysAnalyzed: Int
    ): AiAnalysis {
        val cleanModel = modelName.removePrefix("models/")
        val endpoint = "$BASE_URL/models/$cleanModel:generateContent?key=$apiKey"
        val requestBody = buildRequestBody(prompt)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = readStream(connection.inputStream)
                return parseGeminiResponse(responseText, episodesAnalyzed, healthDaysAnalyzed, cleanModel)
            } else {
                val errorText = readStream(connection.errorStream)
                val friendlyMessage = parseErrorMessage(statusCode, errorText)
                throw GeminiException(friendlyMessage, statusCode)
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw GeminiException("Request to Gemini API timed out for model $cleanModel.", 504)
        } catch (e: java.net.UnknownHostException) {
            throw GeminiException("Cannot reach Gemini API. Please check your internet connection.", null)
        } catch (e: java.io.IOException) {
            throw GeminiException("Network communication error: ${e.localizedMessage ?: "Unknown error"}", null)
        } catch (e: Exception) {
            throw GeminiException("Failed to generate AI insights: ${e.localizedMessage ?: "Unknown error"}", null)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(prompt: String): String {
        val root = JSONObject()

        val contents = JSONArray()
        val contentObj = JSONObject()
        contentObj.put("role", "user")

        val parts = JSONArray()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        parts.put(partObj)

        contentObj.put("parts", parts)
        contents.put(contentObj)
        root.put("contents", contents)

        val generationConfig = JSONObject()
        generationConfig.put("temperature", 0.2)
        generationConfig.put("maxOutputTokens", 8192)
        generationConfig.put("responseMimeType", "application/json")
        root.put("generationConfig", generationConfig)

        return root.toString()
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    /**
     * Parses the Gemini API response structure and extracts the structured JSON analysis.
     * Uses resilient sanitization and regex extraction to ensure malformed or unescaped strings
     * from the model never cause JSON parsing errors.
     */
    fun parseGeminiResponse(
        responseJson: String,
        episodesAnalyzed: Int,
        healthDaysAnalyzed: Int,
        modelUsed: String = ""
    ): AiAnalysis {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw GeminiException("Gemini returned an empty response.")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            throw GeminiException("Gemini response missing content parts.")
        }

        // Concatenate all text parts (skipping thought blocks if present)
        val rawText = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i)
                if (part != null && !part.optBoolean("thought", false)) {
                    append(part.optString("text", ""))
                }
            }
        }.trim()

        if (rawText.isEmpty()) {
            throw GeminiException("Empty text returned by Gemini.")
        }

        val sanitized = sanitizeJson(rawText)

        return try {
            val json = JSONObject(sanitized)
            val headline = json.optString("headline", "").trim()
            val summary = json.optString("summary", "").trim()
            val detailed = json.optString("detailedExplanation", "").trim()
            val hasEnough = json.optBoolean("hasEnoughData", true)

            val triggerPatterns = mutableListOf<String>()
            val tpArray = json.optJSONArray("triggerPatterns")
            if (tpArray != null) {
                for (i in 0 until tpArray.length()) {
                    val item = tpArray.optString(i, "").trim()
                    if (item.isNotEmpty()) triggerPatterns.add(item)
                }
            }

            val precautions = mutableListOf<String>()
            val pcArray = json.optJSONArray("precautions")
            if (pcArray != null) {
                for (i in 0 until pcArray.length()) {
                    val item = pcArray.optString(i, "").trim()
                    if (item.isNotEmpty()) precautions.add(item)
                }
            }

            val warningBulletPoints = mutableListOf<String>()
            val wbArray = json.optJSONArray("warningBulletPoints")
            if (wbArray != null) {
                for (i in 0 until wbArray.length()) {
                    val item = wbArray.optString(i, "").trim()
                    if (item.isNotEmpty()) warningBulletPoints.add(item)
                }
            }

            val reliefSteps = mutableListOf<String>()
            val rsArray = json.optJSONArray("reliefSteps")
            if (rsArray != null) {
                for (i in 0 until rsArray.length()) {
                    val item = rsArray.optString(i, "").trim()
                    if (item.isNotEmpty()) reliefSteps.add(item)
                }
            }

            AiAnalysis(
                headline = headline,
                reliefSteps = reliefSteps,
                summary = summary.ifEmpty { "Analysis completed." },
                detailedExplanation = detailed,
                triggerPatterns = triggerPatterns,
                precautions = precautions,
                warningBulletPoints = warningBulletPoints,
                hasEnoughData = hasEnough,
                generatedAt = System.currentTimeMillis(),
                episodesAnalyzed = episodesAnalyzed,
                healthDaysAnalyzed = healthDaysAnalyzed,
                modelUsed = modelUsed
            )
        } catch (_: Exception) {
            // Resilient fallback parser
            fallbackExtractAnalysis(rawText, episodesAnalyzed, healthDaysAnalyzed, modelUsed)
        }
    }

    /**
     * Cleans raw markdown fences and safely escapes literal newlines, tabs, and unescaped
     * control characters inside JSON string literals.
     */
    fun sanitizeJson(rawText: String): String {
        var text = rawText
        if (text.contains("```")) {
            val match = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(text)
            text = match?.groupValues?.get(1) ?: text.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*```$"), "")
        }
        text = text.trim()

        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }

        val sb = StringBuilder(text.length + 32)
        var inString = false
        var escape = false

        for (i in 0 until text.length) {
            val c = text[i]
            if (escape) {
                sb.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                sb.append(c)
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                sb.append(c)
                continue
            }
            if (inString) {
                when (c) {
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            sb.append("\\u%04x".format(c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Fallback resilient extractor that uses regex to extract structured fields if standard
     * JSON parsing encounters truncated or malformed output.
     */
    fun fallbackExtractAnalysis(
        rawText: String,
        episodesAnalyzed: Int,
        healthDaysAnalyzed: Int,
        modelUsed: String
    ): AiAnalysis {
        val clean = rawText.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "").trim()

        fun extractField(fieldName: String): String {
            val pattern = Regex("\"$fieldName\"\\s*:\\s*\"([^\"]*)\"", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(clean)
            if (match != null) return match.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").trim()

            // If string was truncated without closing quote
            val openPattern = Regex("\"$fieldName\"\\s*:\\s*\"([^,}\\]]+)", RegexOption.DOT_MATCHES_ALL)
            val openMatch = openPattern.find(clean)
            return openMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim() ?: ""
        }

        fun extractArray(fieldName: String): List<String> {
            val pattern = Regex("\"$fieldName\"\\s*:\\s*\\[([^\\]]*)\\]", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(clean) ?: return emptyList()
            val body = match.groupValues[1]
            val items = mutableListOf<String>()
            val itemPattern = Regex("\"([^\"]*)\"")
            itemPattern.findAll(body).forEach { m ->
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty()) items.add(v)
            }
            return items
        }

        val headline = extractField("headline")
        val summary = extractField("summary")
        val detailed = extractField("detailedExplanation")
        val triggers = extractArray("triggerPatterns")
        val precautions = extractArray("precautions")
        val warnings = extractArray("warningBulletPoints")

        val hasEnough = !clean.contains("\"hasEnoughData\"\\s*:\\s*false".toRegex())

        val finalSummary = when {
            summary.isNotBlank() -> summary
            detailed.isNotBlank() -> detailed
            else -> clean.take(200)
        }

        return AiAnalysis(
            headline = headline,
            reliefSteps = extractArray("reliefSteps"),
            summary = finalSummary.ifEmpty { "Analysis generated from recorded migraine history." },
            detailedExplanation = detailed,
            triggerPatterns = triggers,
            precautions = precautions,
            warningBulletPoints = warnings,
            hasEnoughData = hasEnough,
            generatedAt = System.currentTimeMillis(),
            episodesAnalyzed = episodesAnalyzed,
            healthDaysAnalyzed = healthDaysAnalyzed,
            modelUsed = modelUsed
        )
    }

    // ───────────────────────── Follow-up chat ─────────────────────────

    /**
     * Answers a follow-up question about the current insight as plain text.
     * [history] is the earlier conversation (oldest first) and must end with the new user message.
     * Tries [preferredModel] first, then falls back through live / known models on transient errors.
     */
    open suspend fun generateChatReply(
        apiKey: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        preferredModel: String? = null,
        onModelChosen: ((String) -> Unit)? = null
    ): String {
        val key = apiKey.trim()
        if (key.isEmpty()) throw GeminiException("No Gemini API key provided. Please configure it in Settings.")

        val primary = (preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_PRIMARY_MODEL).removePrefix("models/")
        val tried = mutableSetOf(primary)
        var last: GeminiException? = null

        try {
            return chatWithRetry(primary, key, systemPrompt, history).also { onModelChosen?.invoke(primary) }
        } catch (e: GeminiException) {
            last = e
            if (!isFallbackWarranted(e.statusCode, e.message)) throw e
        }

        val liveRaw = try { listAvailableModels(key) } catch (_: Exception) { emptyList() }
        val live = liveRaw.map { it.removePrefix("models/") }.sortedByDescending { scoreModel(it) }
        for (candidate in (live + FALLBACK_CANDIDATE_MODELS).distinct().filter { tried.add(it) }) {
            try {
                return chatWithRetry(candidate, key, systemPrompt, history).also { onModelChosen?.invoke(candidate) }
            } catch (e: GeminiException) {
                last = e
                if (!isFallbackWarranted(e.statusCode, e.message)) throw e
            }
        }
        throw last ?: GeminiException("Gemini service is temporarily unavailable. Please try again shortly.", 503)
    }

    private suspend fun chatWithRetry(model: String, apiKey: String, systemPrompt: String, history: List<AiChatMessage>): String {
        var lastErr: GeminiException? = null
        for (attempt in 1..2) {
            try {
                return executeChat(model, apiKey, systemPrompt, history)
            } catch (e: GeminiException) {
                lastErr = e
                val c = e.statusCode
                val transient = c == 503 || c == 429 || c == 500 || c == 502 || c == 504
                if (!transient || attempt == 2) throw e
                delay(1000L * attempt)
            }
        }
        throw lastErr ?: GeminiException("Failed to generate reply with model $model")
    }

    open fun executeChat(model: String, apiKey: String, systemPrompt: String, history: List<AiChatMessage>): String {
        val cleanModel = model.removePrefix("models/")
        val endpoint = "$BASE_URL/models/$cleanModel:generateContent?key=$apiKey"
        val body = buildChatRequestBody(systemPrompt, history)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
            val code = connection.responseCode
            if (code in 200..299) return parseChatResponse(readStream(connection.inputStream))
            throw GeminiException(parseErrorMessage(code, readStream(connection.errorStream)), code)
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw GeminiException("Request to Gemini API timed out for model $cleanModel.", 504)
        } catch (e: java.net.UnknownHostException) {
            throw GeminiException("Cannot reach Gemini API. Please check your internet connection.", null)
        } catch (e: java.io.IOException) {
            throw GeminiException("Network communication error: ${e.localizedMessage ?: "Unknown error"}", null)
        } catch (e: Exception) {
            throw GeminiException("Failed to get a reply: ${e.localizedMessage ?: "Unknown error"}", null)
        } finally {
            connection.disconnect()
        }
    }

    fun buildChatRequestBody(systemPrompt: String, history: List<AiChatMessage>): String {
        val root = JSONObject()
        root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))

        val contents = JSONArray()
        history.filter { !it.isError && it.text.isNotBlank() }.forEach { m ->
            contents.put(JSONObject()
                .put("role", if (m.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        root.put("contents", contents)
        root.put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1024))
        return root.toString()
    }

    fun parseChatResponse(responseJson: String): String {
        val candidates = JSONObject(responseJson).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw GeminiException("Gemini returned an empty response.")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) throw GeminiException("Gemini response missing content parts.")
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i)
                if (part != null && !part.optBoolean("thought", false)) append(part.optString("text", ""))
            }
        }.trim()
        if (text.isEmpty()) throw GeminiException("Empty text returned by Gemini.")
        return text
    }

    private fun parseErrorMessage(code: Int, errorBody: String): String {
        var detail: String? = null
        if (errorBody.isNotEmpty()) {
            try {
                val json = JSONObject(errorBody)
                val errorObj = json.optJSONObject("error")
                detail = errorObj?.optString("message")
            } catch (_: Exception) {
                detail = null
            }
        }

        return when (code) {
            400 -> detail ?: "Bad request to Gemini API (code 400)."
            401, 403 -> "Invalid Gemini API key. Please verify your key in Settings."
            404 -> detail ?: "Model not found or not supported for generateContent (code 404)."
            429 -> if (!detail.isNullOrBlank()) "Gemini rate limit: $detail" else "Gemini API quota or rate limit exceeded. Please try again later."
            503 -> if (!detail.isNullOrBlank()) "Gemini service unavailable (503): $detail" else "Gemini service is temporarily overloaded (code 503). Please try again shortly."
            500 -> if (!detail.isNullOrBlank()) "Gemini internal error: $detail" else "Gemini service encountered an internal error (code 500). Please try again shortly."
            502, 504 -> detail ?: "Gemini gateway error (code $code). Please try again shortly."
            else -> detail ?: "Gemini API error ($code)."
        }
    }
}

class GeminiException(message: String, val statusCode: Int? = null) : Exception(message)
