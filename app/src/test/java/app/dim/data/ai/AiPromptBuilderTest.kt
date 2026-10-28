package app.dim.data.ai

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.health.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

    @Test
    fun `data fingerprint changes when new finished episode is added`() {
        val ep1 = Episode(id = 1, startedAt = 1000L, endedAt = 5000L, uid = "u1")
        val ep2 = Episode(id = 2, startedAt = 6000L, endedAt = 9000L, uid = "u2")
        val profile = Profile(diagnosis = "Episodic migraine")
        val healthDays = emptyList<HealthDay>()

        val fp1 = AiPromptBuilder.computeDataFingerprint(listOf(ep1), healthDays, profile)
        val fp2 = AiPromptBuilder.computeDataFingerprint(listOf(ep1, ep2), healthDays, profile)

        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `data fingerprint remains identical for unchanged data`() {
        val ep1 = Episode(id = 1, startedAt = 1000L, endedAt = 5000L, uid = "u1")
        val profile = Profile(diagnosis = "Episodic migraine")
        val healthDays = listOf(HealthDay(day = 20000L, sleepMinutes = 450, syncedAt = 20001L))

        val fp1 = AiPromptBuilder.computeDataFingerprint(listOf(ep1), healthDays, profile)
        val fp2 = AiPromptBuilder.computeDataFingerprint(listOf(ep1), healthDays, profile)

        assertEquals(fp1, fp2)
    }

    @Test
    fun `prompt includes episode triggers symptoms and health sleep metrics`() {
        val ep1 = Episode(
            id = 1,
            startedAt = 1700000000000L,
            endedAt = 1700014400000L,
            pain = 8,
            stress = 7,
            triggers = "Lack of sleep,Stress",
            symptoms = "Photophobia,Throbbing",
            startedInSleep = true,
            aura = true,
            hydration = 0,
            meal = 2
        )
        val ep2 = Episode(
            id = 2,
            startedAt = 1700100000000L,
            endedAt = 1700114400000L,
            pain = 6,
            stress = 5,
            triggers = "Lack of sleep",
            symptoms = "Nausea",
            startedInSleep = false,
            aura = false
        )
        val profile = Profile(
            diagnosis = "Chronic migraine",
            conditions = "Anxiety,Sleep problems"
        )
        val healthDays = listOf(
            HealthDay(day = 19680L, sleepMinutes = 320, restingHr = 72, hrvRmssd = 35.0, syncedAt = 100L)
        )

        val prompt = AiPromptBuilder.buildPrompt(listOf(ep1, ep2), healthDays, profile)

        // Verify rules and grounding instructions
        assertTrue(prompt.contains("GROUNDING: Ground every single finding strictly"))
        assertTrue(prompt.contains("CORRELATION VS CAUSATION"))
        assertTrue(prompt.contains("OUTPUT JSON SCHEMA"))

        // Verify data items
        assertTrue(prompt.contains("Chronic migraine"))
        assertTrue(prompt.contains("Lack of sleep"))
        assertTrue(prompt.contains("Photophobia"))
        assertTrue(prompt.contains("Average Pain Rating"))
        assertTrue(prompt.contains("Average Stress Rating"))
        assertTrue(prompt.contains("Attacks starting during sleep: 1 of 2"))
        assertTrue(prompt.contains("Sleep Comparison"))
    }

    @Test
    fun `prompt handles zero finished episodes safely`() {
        val prompt = AiPromptBuilder.buildPrompt(emptyList(), emptyList(), Profile())
        assertTrue(prompt.contains("No completed migraine episodes have been logged yet."))
        assertTrue(prompt.contains("No Health Connect metrics currently synced."))
    }
}
