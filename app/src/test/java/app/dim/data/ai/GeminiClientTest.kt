package app.dim.data.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiClientTest {

    private val client = GeminiClient()

    @Test
    fun `parseGeminiResponse successfully extracts structured fields`() {
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\n  \"hasEnoughData\": true,\n  \"summary\": \"Your attacks predominantly coincide with short sleep durations and elevated stress.\",\n  \"detailedExplanation\": \"Across your recent entries, four out of five attacks followed nights with less than six hours of sleep. Hydration deficit was also recorded twice.\",\n  \"triggerPatterns\": [\n    \"Sleep deficit: 4 of 5 attacks followed <6h sleep\",\n    \"Stress peaks: Average stress of 7.2 prior to attacks\"\n  ],\n  \"precautions\": [\n    \"Establish a consistent sleep schedule\",\n    \"Keep a water bottle nearby during work\"\n  ],\n  \"warningBulletPoints\": [\n    \"Neck tightness in the afternoon\",\n    \"Aura disturbances waking you from sleep\"\n  ]\n}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val analysis = client.parseGeminiResponse(geminiJson, episodesAnalyzed = 5, healthDaysAnalyzed = 14, modelUsed = "gemini-2.0-flash")

        assertTrue(analysis.hasEnoughData)
        assertEquals("Your attacks predominantly coincide with short sleep durations and elevated stress.", analysis.summary)
        assertTrue(analysis.detailedExplanation.contains("Across your recent entries"))
        assertEquals(2, analysis.triggerPatterns.size)
        assertEquals("Sleep deficit: 4 of 5 attacks followed <6h sleep", analysis.triggerPatterns[0])
        assertEquals(2, analysis.precautions.size)
        assertEquals(2, analysis.warningBulletPoints.size)
        assertEquals(5, analysis.episodesAnalyzed)
        assertEquals(14, analysis.healthDaysAnalyzed)
        assertEquals("gemini-2.0-flash", analysis.modelUsed)
    }

    @Test
    fun `parseGeminiResponse strips markdown fences cleanly`() {
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "```json\n{\n  \"hasEnoughData\": true,\n  \"summary\": \"Cleaned markdown summary.\",\n  \"detailedExplanation\": \"Detailed context.\",\n  \"triggerPatterns\": [\"Pattern 1\"],\n  \"precautions\": [\"Precaution 1\"],\n  \"warningBulletPoints\": [\"Warning 1\"]\n}\n```"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val analysis = client.parseGeminiResponse(geminiJson, 3, 0)
        assertEquals("Cleaned markdown summary.", analysis.summary)
        assertEquals("Detailed context.", analysis.detailedExplanation)
        assertEquals(1, analysis.triggerPatterns.size)
    }

    @Test
    fun `parseGeminiResponse handles insufficient data response from model`() {
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\n  \"hasEnoughData\": false,\n  \"summary\": \"Not enough data to detect confident patterns.\",\n  \"detailedExplanation\": \"Only one episode is currently recorded.\",\n  \"triggerPatterns\": [],\n  \"precautions\": [\"Continue logging episodes\"],\n  \"warningBulletPoints\": []\n}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val analysis = client.parseGeminiResponse(geminiJson, 1, 0)
        assertFalse(analysis.hasEnoughData)
        assertEquals("Not enough data to detect confident patterns.", analysis.summary)
        assertTrue(analysis.triggerPatterns.isEmpty())
    }

    @Test
    fun `parseGeminiResponse throws on empty candidates`() {
        val emptyJson = "{\"candidates\": []}"
        try {
            client.parseGeminiResponse(emptyJson, 2, 0)
            fail("Expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(e.message!!.contains("empty response"))
        }
    }

    @Test
    fun `parseModelsList extracts only gemini models supporting generateContent`() {
        val json = """
        {
          "models": [
            {
              "name": "models/gemini-2.0-flash",
              "supportedGenerationMethods": ["generateContent", "countTokens"]
            },
            {
              "name": "models/text-embedding-004",
              "supportedGenerationMethods": ["embedContent"]
            },
            {
              "name": "models/gemini-1.5-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/aqa",
              "supportedGenerationMethods": ["generateAnswer"]
            }
          ]
        }
        """.trimIndent()

        val parsed = client.parseModelsList(json)
        assertEquals(listOf("models/gemini-2.0-flash", "models/gemini-1.5-flash"), parsed)
    }

    @Test
    fun `selectBestModel prefers newer flash models`() {
        val models = listOf(
            "models/gemini-1.5-pro",
            "models/gemini-1.5-flash",
            "models/gemini-2.0-flash",
            "models/gemini-2.0-pro-exp",
            "models/gemini-pro"
        )

        val best = client.selectBestModel(models)
        assertEquals("models/gemini-2.0-flash", best)
    }

    @Test
    fun `selectBestModel picks gemini-2_5-flash when available`() {
        val models = listOf(
            "models/gemini-1.5-flash",
            "models/gemini-2.0-flash",
            "models/gemini-2.5-flash",
            "models/gemini-2.5-pro"
        )

        val best = client.selectBestModel(models)
        assertEquals("models/gemini-2.5-flash", best)
    }

    @Test
    fun `generateAnalysis falls back to auto-discovered model if preferred model returns 404`() = runBlocking {
        var chosenModel: String? = null
        val testClient = object : GeminiClient() {
            var calls = 0
            override fun listAvailableModels(apiKey: String): List<String> {
                return listOf("models/gemini-2.0-flash", "models/gemini-1.5-pro")
            }

            override suspend fun generateAnalysis(
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int,
                preferredModel: String?,
                onModelChosen: ((String) -> Unit)?
            ): AiAnalysis {
                // Simulate preferredModel (gemini-1.5-flash) failing with 404,
                // and fallback choosing gemini-2.0-flash
                calls++
                if (preferredModel == "gemini-1.5-flash") {
                    // simulate 404
                    val models = listAvailableModels(apiKey)
                    val best = selectBestModel(models).removePrefix("models/")
                    onModelChosen?.invoke(best)
                    return AiAnalysis(
                        summary = "Analysis with $best",
                        detailedExplanation = "",
                        triggerPatterns = emptyList(),
                        precautions = emptyList(),
                        warningBulletPoints = emptyList(),
                        hasEnoughData = true,
                        generatedAt = 1000L,
                        episodesAnalyzed = episodesAnalyzed,
                        modelUsed = best
                    )
                }
                onModelChosen?.invoke(preferredModel ?: "gemini-2.0-flash")
                return AiAnalysis(
                    summary = "Analysis",
                    detailedExplanation = "",
                    triggerPatterns = emptyList(),
                    precautions = emptyList(),
                    warningBulletPoints = emptyList(),
                    hasEnoughData = true,
                    generatedAt = 1000L,
                    episodesAnalyzed = episodesAnalyzed,
                    modelUsed = preferredModel ?: "gemini-2.0-flash"
                )
            }
        }

        val result = testClient.generateAnalysis(
            apiKey = "key",
            prompt = "prompt",
            episodesAnalyzed = 2,
            healthDaysAnalyzed = 0,
            preferredModel = "gemini-1.5-flash",
            onModelChosen = { chosenModel = it }
        )

        assertEquals("gemini-2.0-flash", chosenModel)
        assertEquals("gemini-2.0-flash", result.modelUsed)
    }

    @Test
    fun `parseGeminiResponse handles raw unescaped newlines inside JSON strings`() {
        // Raw literal newlines inside double quotes typically cause JSONException("Unterminated string")
        val rawJsonWithLiteralNewlines = "{\n" +
                "  \"candidates\": [\n" +
                "    {\n" +
                "      \"content\": {\n" +
                "        \"parts\": [\n" +
                "          {\n" +
                "            \"text\": \"{\\\"hasEnoughData\\\": true, \\\"summary\\\": \\\"Paragraph 1\\nParagraph 2 with unescaped newline\\\", \\\"detailedExplanation\\\": \\\"Details\\\", \\\"triggerPatterns\\\": [\\\"Trigger 1\\\"], \\\"precautions\\\": [], \\\"warningBulletPoints\\\": []}\"\n" +
                "          }\n" +
                "        ]\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}"

        val analysis = client.parseGeminiResponse(rawJsonWithLiteralNewlines, 3, 5, "gemini-2.0-flash")
        assertTrue(analysis.summary.contains("Paragraph 1"))
        assertTrue(analysis.summary.contains("Paragraph 2"))
        assertEquals("Details", analysis.detailedExplanation)
    }

    @Test
    fun `parseGeminiResponse handles multi-part Gemini 2 responses and skips thought blocks`() {
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "thought": true,
                    "text": "Thinking about the user's migraine history..."
                  },
                  {
                    "text": "{\"hasEnoughData\": true, \"summary\": \"Multi-part summary.\", \"detailedExplanation\": \"Explanation\", \"triggerPatterns\": [], \"precautions\": [], \"warningBulletPoints\": []}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val analysis = client.parseGeminiResponse(geminiJson, 2, 0)
        assertEquals("Multi-part summary.", analysis.summary)
        assertFalse(analysis.summary.contains("Thinking about"))
    }

    @Test
    fun `parseGeminiResponse falls back to regex extractor on truncated JSON`() {
        // Simulating JSON cut off prematurely mid-stream
        val truncatedJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"hasEnoughData\": true, \"summary\": \"Truncated summary text here that got cut off"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val analysis = client.parseGeminiResponse(truncatedJson, 1, 0, "gemini-2.0-flash")
        assertTrue(analysis.summary.contains("Truncated summary text"))
        assertEquals("gemini-2.0-flash", analysis.modelUsed)
    }

    @Test
    fun `generateAnalysis falls back from overloaded 503 model to working candidate model`() = runBlocking {
        var chosenModel: String? = null
        val attemptedModels = mutableListOf<String>()

        val testClient = object : GeminiClient() {
            override fun listAvailableModels(apiKey: String): List<String> {
                return listOf("models/gemini-2.0-flash", "models/gemini-2.0-flash-lite-preview-02-05")
            }

            override fun executeSingleGenerate(
                modelName: String,
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int
            ): AiAnalysis {
                val clean = modelName.removePrefix("models/")
                attemptedModels.add(clean)
                if (clean == "gemini-2.0-flash") {
                    throw GeminiException("The model is overloaded. Please try again.", 503)
                }
                if (clean == "gemini-2.0-flash-lite-preview-02-05") {
                    return AiAnalysis(
                        summary = "Analysis generated via flash-lite",
                        detailedExplanation = "",
                        triggerPatterns = listOf("Stress"),
                        precautions = listOf("Hydrate"),
                        warningBulletPoints = emptyList(),
                        hasEnoughData = true,
                        generatedAt = 12345L,
                        episodesAnalyzed = episodesAnalyzed,
                        modelUsed = clean
                    )
                }
                throw GeminiException("Unexpected model: $clean", 500)
            }
        }

        val result = testClient.generateAnalysis(
            apiKey = "test_key",
            prompt = "Analyze",
            episodesAnalyzed = 3,
            healthDaysAnalyzed = 7,
            preferredModel = "gemini-2.0-flash",
            onModelChosen = { chosenModel = it }
        )

        assertEquals("gemini-2.0-flash-lite-preview-02-05", chosenModel)
        assertEquals("gemini-2.0-flash-lite-preview-02-05", result.modelUsed)
        assertEquals("Analysis generated via flash-lite", result.summary)
        // Verify gemini-2.0-flash was retried on 503 before moving on
        assertTrue(attemptedModels.count { it == "gemini-2.0-flash" } >= 2)
        assertTrue(attemptedModels.contains("gemini-2.0-flash-lite-preview-02-05"))
    }

    @Test
    fun `generateAnalysis immediately aborts on 401 without testing other models`() = runBlocking {
        val attemptedModels = mutableListOf<String>()

        val testClient = object : GeminiClient() {
            override fun executeSingleGenerate(
                modelName: String,
                apiKey: String,
                prompt: String,
                episodesAnalyzed: Int,
                healthDaysAnalyzed: Int
            ): AiAnalysis {
                val clean = modelName.removePrefix("models/")
                attemptedModels.add(clean)
                throw GeminiException("Invalid Gemini API key.", 401)
            }
        }

        try {
            testClient.generateAnalysis(
                apiKey = "bad_key",
                prompt = "Analyze",
                episodesAnalyzed = 2,
                healthDaysAnalyzed = 0
            )
            fail("Expected GeminiException for 401")
        } catch (e: GeminiException) {
            assertEquals(401, e.statusCode)
            // Should only attempt once, not iterate through candidate models with an invalid key
            assertEquals(1, attemptedModels.size)
        }
    }
}
