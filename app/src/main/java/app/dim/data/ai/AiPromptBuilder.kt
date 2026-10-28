package app.dim.data.ai

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import app.dim.data.health.HealthDay
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

object AiPromptBuilder {

    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH)
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)

    /**
     * Produces a fingerprint of the current dataset to detect whether new data has been added
     * or edited since the previous analysis.
     */
    fun computeDataFingerprint(
        episodes: List<Episode>,
        healthDays: List<HealthDay>,
        profile: Profile,
        meds: MedLists = MedLists()
    ): String {
        val finished = episodes.filter { it.endedAt != null }
        val sb = StringBuilder()
        sb.append("schema=5;").append("ep_count=").append(finished.size).append(";")
        val lastFinished = finished.maxByOrNull { it.endedAt ?: 0L }
        sb.append("last_end=").append(lastFinished?.endedAt ?: 0L).append(";")
        sb.append("hd_count=").append(healthDays.size).append(";")
        val lastHd = healthDays.maxByOrNull { it.day }
        sb.append("last_hd=").append(lastHd?.day ?: 0L).append(";")
        sb.append("profile=").append(profile.name).append("|").append(profile.birthYear).append("|").append(profile.sex).append("|")
            .append(profile.cycleTracking).append("|").append(profile.firstAge).append("|").append(profile.diagnosis).append("|")
            .append(profile.aura).append("|").append(profile.family).append("|").append(profile.conditions).append("|").append(profile.notes)
        sb.append(";notes=").append(finished.joinToString("|") { it.notes.trim() }.hashCode())
        sb.append(";routine=").append(meds.routine.joinToString(",") { it.name + "@" + it.minutes })
        sb.append(";occasional=").append(meds.occasional.joinToString(","))

        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds the complete prompt for Gemini based on recorded episodes, Health Connect metrics,
     * and profile details.
     */
    fun buildPrompt(
        episodes: List<Episode>,
        healthDays: List<HealthDay>,
        profile: Profile,
        meds: MedLists = MedLists()
    ): String {
        val finished = episodes.filter { it.endedAt != null }.sortedBy { it.startedAt }
        val count = finished.size

        val sb = StringBuilder()
        sb.append("You are an evidence-based clinical migraine insights assistant for Dim, a personal migraine tracking app.\n")
        sb.append("Analyze the user's recorded migraine history, symptoms, triggers, and health metrics below.\n\n")

        sb.append("CRITICAL INSTRUCTIONS:\n")
        sb.append("1. GROUNDING: Ground every single finding strictly in the recorded data provided below. NEVER fabricate, hallucinate, or assume triggers, patterns, or numbers not present in the data.\n")
        sb.append("2. CORRELATION VS CAUSATION: Never state that a factor definitely caused an attack. Frame observations as evidence-supported patterns (e.g., 'In 4 out of 5 attacks, stress was recorded', 'Attacks frequently coincided with sleep deficits under 6 hours').\n")
        sb.append("3. INSUFFICIENT DATA: If the user has fewer than 2 finished episodes or the data is too sparse to identify meaningful patterns, set \"hasEnoughData\": false. In the summary, politely explain that more logged entries are needed to discover personalized patterns.\n")
        sb.append("4. TONE: Calm, direct, second person. NEVER tell the user to consult, see or ask a doctor, clinician, physician, specialist or healthcare provider, and add no medical disclaimers anywhere. Do not give medication doses.\n")
        sb.append("5. RESPONSE FORMAT: You MUST return a single valid JSON object with the exact keys described below.\n")
        sb.append("6. ANALYZE EVERYTHING FIRST: Read EVERY episode in the FULL EPISODE LOG and EVERY field of each entry (date, weekday, time of day, duration, gap since previous attack, pain, stress, triggers, symptoms, location, medication, aura, woke-up-with-attack, hydration, time since last meal, caffeine, where the user was when it started, and the sleep / steps / heart-rate / HRV values around that attack) before writing anything. Compare entries with each other and with the aggregate statistics, and look for combinations (e.g. skipped meal + short sleep + screen glare). Base conclusions on the WHOLE log, never on just two or three entries. Only call something a pattern if it appears in at least 2 entries, and say how many (e.g. '3 of 5').\n")
        sb.append("7. PERSONAL, NOT GENERIC: The user already knows generic migraine advice. FORBIDDEN: any tip that is not tied to THIS user's data (for example 'sleep 8 hours', 'stay hydrated', 'reduce stress', 'rest in a dark room', 'avoid triggers' on their own). Every list item must cite a specific time, number, trigger, symptom, location or medication from this user's log and turn it into a concrete, measurable action (e.g. 'Attacks start 6-8 PM after 6+ h without food: eat by 3 PM'). If medication is logged, compare episode duration and pain with vs without it and tell the user when to use what they already take, based on what their own log shows. If a data type is missing, say once what to start logging and why.\n")
        sb.append("8. BE CONCISE: no story, no statistics dump. The summary is 2-3 sentences (about 45-60 words). Every list item is ONE sentence of at most 20 words.\n")
        sb.append("9. EMPHASIS: In \"summary\" and in every list item, wrap the 1-3 key words or actions in **double asterisks** for bold. Use no other markdown.\n\n")
        sb.append("10. MEDICINE DEEP ANALYSIS: If MEDICINE DEEP-DIVE data exists, analyze EACH listed medicine by its exact name. Use your general pharmacological knowledge of that exact medicine (what it is, its class, how it works, how quickly it acts, how long it lasts) together with the user's own numbers: for acute medicines, how much it changed duration and pain versus attacks without medication, whether it is used early or late in the attack, and whether acute-use days in the last 30 days approach medication-overuse territory (about 10+ days/month for triptans, combination analgesics and opioids; about 15+ for simple analgesics and NSAIDs); for routine medicines, whether the scheduled time and attack timing fit its action, whether attacks still occur despite it, and any overlap between scheduled and acute medicines. If a medicine has no logged results, say its effect cannot be judged yet and what to log. Never invent results, never give doses, and say plainly when sample size is too small to conclude. Put this analysis inside reliefSteps (acute medicines), precautions (routine medicines, overuse limits) and the summary, still obeying the one-sentence limit per item and naming the medicine in each.\n")
        sb.append("11. USER NOTES: Episodes may carry a free-text 'user note'. Read every note and treat it as extra evidence: look for repeated words, circumstances, foods, activities, places, people, events or feelings across notes and entries, and combine them with the logged fields to find patterns. Quote or paraphrase a note only when it supports a pattern seen in at least 2 entries, and never invent details beyond what a note says.\n")

        sb.append("OUTPUT JSON SCHEMA:\n")
        sb.append("{\n")
        sb.append("  \"hasEnoughData\": boolean,\n")
        sb.append("  \"headline\": \"ONE short line, max 5 words and 32 characters, naming the main pattern (e.g. 'Evening attacks, skipped meals'). No trailing period.\",\n")
        sb.append("  \"summary\": \"MAX 2 short sentences (under 35 words): the main pattern across the whole log and the single highest-impact fix.\",\n")
        sb.append("  \"detailedExplanation\": \"\",\n")
        sb.append("  \"reliefSteps\": [\"3-4 steps for the moment an attack starts, built from THIS user's log: their usual onset time, symptoms, location and what their logged medication did to duration and pain\"],\n")
        sb.append("  \"precautions\": [\"3-5 prevention rules, each citing the user's own data and giving a measurable action, limit or time\"],\n")
        sb.append("  \"triggerPatterns\": [\"2-4 of the user's own most repeated triggers or trigger combinations, each as 'Trigger (n of N): the exact way to cut it'\"],\n")
        sb.append("  \"warningBulletPoints\": [\"2-3 early signs taken from the user's own logged symptom and timing pattern, each with what to do right then\"]\n")
        sb.append("}\n\n")

        // User Profile Section
        sb.append("=== USER PROFILE ===\n")
        if (profile.name.isNotBlank()) sb.append("- Name: ").append(profile.name).append("\n")
        profile.birthYear?.let { sb.append("- Age: ").append(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - it).append("\n") }
        if (profile.sex.isNotBlank()) {
            sb.append("- Sex: ").append(profile.sex).append("\n")
            if (profile.sex == "Female") sb.append("- Menstrual cycle tracking: ").append(if (profile.cycleTracking) "on" else "off").append("\n")
        }
        profile.firstAge?.let { sb.append("- Age at first migraine: ").append(it).append("\n") }
        if (profile.diagnosis.isNotBlank()) sb.append("- Diagnosis: ").append(profile.diagnosis).append("\n")
        if (profile.aura.isNotBlank()) sb.append("- Aura tendency: ").append(profile.aura).append("\n")
        if (profile.conditions.isNotBlank()) sb.append("- Related conditions: ").append(profile.conditions).append("\n")
        if (profile.family.isNotBlank()) sb.append("- Family history: ").append(profile.family).append("\n")
        if (profile.notes.isNotBlank()) sb.append("- User notes: ").append(profile.notes).append("\n")
        if (profile.diagnosis.isBlank() && profile.conditions.isBlank()) sb.append("(No specific medical profile provided)\n")
        sb.append("\n")

        // Medication lists from the Medication screen
        sb.append("=== USER MEDICATION LISTS ===\n")
        if (meds.routine.isEmpty() && meds.occasional.isEmpty()) sb.append("(None added)\n")
        else {
            if (meds.routine.isNotEmpty()) sb.append("- Routine/preventive (taken on a schedule): ")
                .append(meds.routine.joinToString("; ") { "%s at %02d:%02d".format(it.name, it.minutes / 60, it.minutes % 60) }).append("\n")
            if (meds.occasional.isNotEmpty()) sb.append("- Occasional/acute (taken during an attack): ").append(meds.occasional.joinToString("; ")).append("\n")
        }
        sb.append("\n")

        // Episode History Section
        sb.append("=== RECORDED MIGRAINE EPISODES (Total finished: ").append(count).append(") ===\n")
        if (finished.isEmpty()) {
            sb.append("No completed migraine episodes have been logged yet.\n\n")
        } else {
            // Aggregate frequencies
            val allTriggers = finished.flatMap { it.triggers.split(",").map { t -> t.trim() }.filter { t -> t.isNotEmpty() } }
            val triggerCounts = allTriggers.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

            val allSymptoms = finished.flatMap { it.symptoms.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() } }
            val symptomCounts = allSymptoms.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

            val wokeUpCount = finished.count { it.startedInSleep }
            val auraCount = finished.count { it.aura }
            val avgPain = finished.mapNotNull { it.pain }.takeIf { it.isNotEmpty() }?.average()
            val avgStress = finished.mapNotNull { it.stress }.takeIf { it.isNotEmpty() }?.average()

            sb.append("Aggregated Statistics:\n")
            if (avgPain != null) sb.append("- Average Pain Rating: ").append("%.1f".format(avgPain)).append(" / 10\n")
            if (avgStress != null) sb.append("- Average Stress Rating: ").append("%.1f".format(avgStress)).append(" / 10\n")
            sb.append("- Attacks starting during sleep: ").append(wokeUpCount).append(" of ").append(count).append("\n")
            sb.append("- Attacks with aura: ").append(auraCount).append(" of ").append(count).append("\n")

            if (triggerCounts.isNotEmpty()) {
                sb.append("- Most frequent recorded triggers: ")
                sb.append(triggerCounts.joinToString("; ") { "${it.key} (${it.value}/$count episodes, ${(it.value * 100) / count}%)" })
                sb.append("\n")
            }

            if (symptomCounts.isNotEmpty()) {
                sb.append("- Most frequent recorded symptoms: ")
                sb.append(symptomCounts.joinToString("; ") { "${it.key} (${it.value}/$count episodes, ${(it.value * 100) / count}%)" })
                sb.append("\n")
            }

            // Hydration / meal distributions
            val lowHydration = finished.count { it.hydration == 0 }
            val longFasting = finished.count { it.meal == 2 }
            if (lowHydration > 0) sb.append("- Low hydration (<500ml) recorded before ").append(lowHydration).append(" attacks.\n")
            if (longFasting > 0) sb.append("- Prolonged fasting (>6h without meal) recorded before ").append(longFasting).append(" attacks.\n")

            // Timing, spacing and medication outcome statistics
            val zoneId = ZoneId.systemDefault()
            fun hourOf(ep: Episode) = Instant.ofEpochMilli(ep.startedAt).atZone(zoneId).hour
            fun partOfDay(h: Int) = when (h) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
            val partCounts = finished.groupingBy { partOfDay(hourOf(it)) }.eachCount().entries.sortedByDescending { it.value }
            sb.append("- Time of day attacks start: ").append(partCounts.joinToString("; ") { "${it.key} (${it.value}/$count)" }).append("\n")
            val weekdayCounts = finished.groupingBy { Instant.ofEpochMilli(it.startedAt).atZone(zoneId).dayOfWeek.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
                .eachCount().entries.sortedByDescending { it.value }
            sb.append("- Weekdays: ").append(weekdayCounts.joinToString("; ") { "${it.key} (${it.value})" }).append("\n")
            val durations = finished.mapNotNull { e -> e.endedAt?.let { (it - e.startedAt) / 60000 } }
            if (durations.isNotEmpty()) {
                sb.append("- Duration: average ${durations.average().toInt() / 60}h ${durations.average().toInt() % 60}m, shortest ${durations.min() / 60}h ${durations.min() % 60}m, longest ${durations.max() / 60}h ${durations.max() % 60}m\n")
            }
            if (finished.size > 1) {
                val gaps = finished.zipWithNext { x, y -> (y.startedAt - x.startedAt) / 86_400_000L }
                sb.append("- Days between attacks: average %.1f, shortest %d, longest %d\n".format(gaps.average(), gaps.min(), gaps.max()))
            }
            fun lbl(v: Int?, labels: List<String>) = v?.let { labels.getOrNull(it) }
            val hydLabels = listOf("<500 ml", "500 ml-1 L", ">1 L")
            val mealLabels = listOf("<2 h since last meal", "2-6 h since last meal", ">6 h since last meal")
            val cafLabels = listOf("no caffeine", "1 caffeinated drink", "more than 1 caffeinated drink")
            listOf("Hydration before attack" to finished.mapNotNull { lbl(it.hydration, hydLabels) },
                "Last meal before attack" to finished.mapNotNull { lbl(it.meal, mealLabels) },
                "Caffeine before attack" to finished.mapNotNull { lbl(it.caffeine, cafLabels) }).forEach { (name, vals) ->
                if (vals.isNotEmpty()) sb.append("- ").append(name).append(": ").append(vals.groupingBy { it }.eachCount().entries.joinToString("; ") { "${it.key} (${it.value})" }).append("\n")
            }
            val places = finished.map { it.onsetPlace }.filter { it.isNotBlank() }
            if (places.isNotEmpty()) sb.append("- Where the user was when attacks started: ").append(places.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} (${it.value})" }).append("\n")
            val medEpisodes = finished.filter { it.medication.isNotBlank() }
            val noMed = finished.filter { it.medication.isBlank() }
            fun avgDur(l: List<Episode>) = l.mapNotNull { e -> e.endedAt?.let { (it - e.startedAt) / 60000 } }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            if (medEpisodes.isNotEmpty()) {
                medEpisodes.flatMap { e -> e.medication.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it to e } }
                    .groupBy({ it.first }, { it.second }).forEach { (med, eps) ->
                        val d = avgDur(eps); val pn = eps.mapNotNull { it.pain }.takeIf { it.isNotEmpty() }?.average()
                        sb.append("- Medication '").append(med).append("': used in ").append(eps.size).append(" attacks")
                        if (d != null) sb.append(", average duration ${d / 60}h ${d % 60}m")
                        if (pn != null) sb.append(", average pain %.1f".format(pn))
                        sb.append("\n")
                    }
            }
            if (noMed.isNotEmpty()) {
                val d = avgDur(noMed); val pn = noMed.mapNotNull { it.pain }.takeIf { it.isNotEmpty() }?.average()
                sb.append("- No medication logged: ").append(noMed.size).append(" attacks")
                if (d != null) sb.append(", average duration ${d / 60}h ${d % 60}m")
                if (pn != null) sb.append(", average pain %.1f".format(pn))
                sb.append("\n")
            }

            // Deep per-medicine breakdown for every medicine on the user's Medication lists
            if (meds.routine.isNotEmpty() || meds.occasional.isNotEmpty()) {
                sb.append("\nMEDICINE DEEP-DIVE (every medicine the user added, matched by name against the medication logged on episodes):\n")
                val baseDur = avgDur(noMed); val basePain = noMed.mapNotNull { it.pain }.takeIf { it.isNotEmpty() }?.average()
                val dayMs = 86_400_000L
                val now = System.currentTimeMillis()
                fun epsFor(name: String) = finished.filter { e -> e.medication.split(",").any { it.trim().equals(name, ignoreCase = true) } }
                fun stats(name: String, kind: String, extra: (List<Episode>) -> String) {
                    val eps = epsFor(name)
                    sb.append("- ").append(name).append(" (").append(kind).append("): ")
                    if (eps.isEmpty()) { sb.append("never logged on any episode, so its effect cannot be measured yet.").append(extra(eps)).append("\n"); return }
                    val d = avgDur(eps); val pn = eps.mapNotNull { it.pain }.takeIf { it.isNotEmpty() }?.average()
                    sb.append("logged on ").append(eps.size).append(" of ").append(count).append(" attacks")
                    if (d != null) sb.append(", avg duration ${d / 60}h ${d % 60}m")
                    if (pn != null) sb.append(", avg pain %.1f".format(pn))
                    if (baseDur != null && d != null) sb.append(" (attacks with no medication: avg ${baseDur / 60}h ${baseDur % 60}m")
                    if (basePain != null) sb.append(if (baseDur != null && d != null) ", pain %.1f)".format(basePain) else " (attacks with no medication: avg pain %.1f)".format(basePain))
                    else if (baseDur != null && d != null) sb.append(")")
                    val last30 = eps.count { now - it.startedAt <= 30 * dayMs }
                    sb.append("; used on ").append(last30).append(" attacks in the last 30 days")
                    val others = eps.filter { e -> e.medication.split(",").map { it.trim() }.filter { it.isNotEmpty() }.size > 1 }
                    if (others.isNotEmpty()) sb.append("; taken together with another medicine in ").append(others.size).append(" of them")
                    sb.append(extra(eps)).append("\n")
                }
                meds.routine.forEach { r ->
                    val t = "%02d:%02d".format(r.minutes / 60, r.minutes % 60)
                    stats(r.name, "routine/preventive, scheduled daily at $t") { _ ->
                        val within = finished.count { e -> val h = hourOf(e) * 60 + Instant.ofEpochMilli(e.startedAt).atZone(zoneId).minute
                            ((h - r.minutes + 1440) % 1440) in 0..360 }
                        "; ${within} of $count attacks started within 6 h after this scheduled time"
                    }
                }
                meds.occasional.forEach { o ->
                    stats(o, "occasional/acute, taken during attacks") { eps ->
                        val days = eps.map { Instant.ofEpochMilli(it.startedAt).atZone(zoneId).toLocalDate() }.filter { it.isAfter(LocalDate.now(zoneId).minusDays(30)) }.distinct().size
                        "; acute-use days in the last 30 days: $days"
                    }
                }
                val listed = (meds.routine.map { it.name } + meds.occasional).map { it.lowercase() }.toSet()
                val unlisted = medEpisodes.flatMap { e -> e.medication.split(",").map { it.trim() }.filter { it.isNotEmpty() } }.filter { it.lowercase() !in listed }.distinct()
                if (unlisted.isNotEmpty()) sb.append("- Logged on episodes but not on the Medication lists: ").append(unlisted.joinToString(", ")).append("\n")
            }

            // Every episode with all of its fields plus the health context around it
            val healthByDay = healthDays.associateBy { it.day }
            val dayFmt = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.ENGLISH)
            sb.append("\nFULL EPISODE LOG (every finished episode, oldest to newest; analyze each one):\n")
            val shown = if (finished.size > 80) finished.takeLast(80) else finished
            shown.forEachIndexed { idx, ep ->
                val start = Date(ep.startedAt)
                val durMinutes = ep.endedAt?.let { (it - ep.startedAt) / 60000 }
                val durStr = if (durMinutes != null) "${durMinutes / 60}h ${durMinutes % 60}m" else "unknown"
                val prev = shown.getOrNull(idx - 1)

                sb.append("Episode #").append(idx + 1).append(": ")
                sb.append(dayFmt.format(start)).append(" at ").append(timeFormat.format(start)).append(" (").append(partOfDay(hourOf(ep))).append(")")
                sb.append(", duration: ").append(durStr)
                if (prev != null) sb.append(", ").append((ep.startedAt - prev.startedAt) / 86_400_000L).append(" days after previous attack")
                if (ep.pain != null) sb.append(", pain: ").append(ep.pain).append("/10")
                if (ep.stress != null) sb.append(", stress: ").append(ep.stress).append("/10")
                if (ep.startedInSleep) sb.append(", [woke up with attack]")
                if (ep.aura) sb.append(", [aura experienced]")
                if (ep.triggers.isNotBlank()) sb.append(", triggers: [").append(ep.triggers).append("]")
                if (ep.symptoms.isNotBlank()) sb.append(", symptoms: [").append(ep.symptoms).append("]")
                if (ep.location.isNotBlank()) sb.append(", location: [").append(ep.location).append("]")
                sb.append(", medication: [").append(ep.medication.ifBlank { "none logged" }).append("]")
                if (ep.notes.isNotBlank()) sb.append(", user note: \"").append(ep.notes.trim().replace("\n", " ")).append("\"")
                lbl(ep.hydration, hydLabels)?.let { sb.append(", hydration: ").append(it) }
                lbl(ep.meal, mealLabels)?.let { sb.append(", last meal: ").append(it) }
                lbl(ep.caffeine, cafLabels)?.let { sb.append(", caffeine: ").append(it) }
                if (ep.onsetPlace.isNotBlank()) sb.append(", started at: ").append(ep.onsetPlace)

                val dayNum = Instant.ofEpochMilli(ep.startedAt).atZone(zoneId).toLocalDate().toEpochDay()
                val h = healthByDay[dayNum]
                val hPrev = healthByDay[dayNum - 1]
                val bits = mutableListOf<String>()
                h?.sleepMinutes?.let { m ->
                    var t = "sleep before attack ${m / 60}h ${m % 60}m"
                    val deep = h?.sleepDeepMinutes; val rem = h?.sleepRemMinutes
                    if (deep != null || rem != null) t += " (deep ${deep ?: 0}m, REM ${rem ?: 0}m)"
                    bits.add(t)
                }
                hPrev?.steps?.let { bits.add("steps day before $it") }
                h?.steps?.let { bits.add("steps attack day $it") }
                h?.restingHr?.let { bits.add("resting HR $it bpm") }
                h?.hrvRmssd?.let { bits.add("HRV %.0f ms".format(it)) }
                if (bits.isNotEmpty()) sb.append(", health: ").append(bits.joinToString("; "))
                sb.append("\n")
            }
            sb.append("\n")
        }

        // Health Connect Metrics Section
        sb.append("=== HEALTH CONNECT METRICS (Total synced days: ").append(healthDays.size).append(") ===\n")
        if (healthDays.isEmpty()) {
            sb.append("No Health Connect metrics currently synced.\n\n")
        } else {
            val zone = ZoneId.systemDefault()
            val attackDates = finished.map {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            }.toSet()

            val attackDaysHealth = healthDays.filter { LocalDate.ofEpochDay(it.day) in attackDates }
            val nonAttackDaysHealth = healthDays.filter { LocalDate.ofEpochDay(it.day) !in attackDates }

            // Sleep comparison
            val attackSleep = attackDaysHealth.mapNotNull { it.sleepMinutes }
            val nonAttackSleep = nonAttackDaysHealth.mapNotNull { it.sleepMinutes }
            if (attackSleep.isNotEmpty() || nonAttackSleep.isNotEmpty()) {
                sb.append("Sleep Comparison:\n")
                if (attackSleep.isNotEmpty()) {
                    val avgAttSleep = attackSleep.average().toInt()
                    sb.append("- Average sleep night before migraine: ${avgAttSleep / 60}h ${avgAttSleep % 60}m (${attackSleep.size} nights recorded)\n")
                }
                if (nonAttackSleep.isNotEmpty()) {
                    val avgNonSleep = nonAttackSleep.average().toInt()
                    sb.append("- Average sleep on migraine-free nights: ${avgNonSleep / 60}h ${avgNonSleep % 60}m (${nonAttackSleep.size} nights recorded)\n")
                }
            }

            // Resting Heart Rate & HRV comparison
            val attackRhr = attackDaysHealth.mapNotNull { it.restingHr }
            val nonAttackRhr = nonAttackDaysHealth.mapNotNull { it.restingHr }
            if (attackRhr.isNotEmpty() && nonAttackRhr.isNotEmpty()) {
                sb.append("- Resting HR: %.1f bpm on attack days vs %.1f bpm on migraine-free days\n".format(attackRhr.average(), nonAttackRhr.average()))
            }

            val attackHrv = attackDaysHealth.mapNotNull { it.hrvRmssd }
            val nonAttackHrv = nonAttackDaysHealth.mapNotNull { it.hrvRmssd }
            if (attackHrv.isNotEmpty() && nonAttackHrv.isNotEmpty()) {
                sb.append("- Heart Rate Variability (HRV): %.1f ms on attack days vs %.1f ms on migraine-free days\n".format(attackHrv.average(), nonAttackHrv.average()))
            }

            // Steps comparison
            val attackSteps = attackDaysHealth.mapNotNull { it.steps }
            val nonAttackSteps = nonAttackDaysHealth.mapNotNull { it.steps }
            if (attackSteps.isNotEmpty() && nonAttackSteps.isNotEmpty()) {
                sb.append("- Daily steps: %.0f on attack days vs %.0f on migraine-free days\n".format(attackSteps.average(), nonAttackSteps.average()))
            }
            sb.append("\n")
        }

        sb.append("Respond now with the requested JSON object.")
        return sb.toString()
    }

    private const val DATA_START = "=== USER PROFILE ==="
    private const val DATA_END = "Respond now with the requested JSON object."

    /**
     * System context for follow-up questions: the same recorded data used for the analysis plus the
     * analysis the user is asking about, with plain-text (non-JSON) answering rules.
     */
    fun buildChatSystemPrompt(
        episodes: List<Episode>,
        healthDays: List<HealthDay>,
        profile: Profile,
        analysis: AiAnalysis?,
        meds: MedLists = MedLists()
    ): String {
        val full = buildPrompt(episodes, healthDays, profile, meds)
        val start = full.indexOf(DATA_START).takeIf { it >= 0 } ?: 0
        val data = full.substring(start).removeSuffix(DATA_END).trim()

        val sb = StringBuilder()
        sb.append("You are an evidence-based migraine insights assistant inside Dim, a personal migraine tracking app. ")
        sb.append("The user is asking follow-up questions about the insight you generated for them.\n\n")
        sb.append("RULES:\n")
        sb.append("1. Ground every answer strictly in the recorded data and insight below. Never invent triggers, numbers, or patterns. If the data cannot answer, say so plainly.\n")
        sb.append("2. Talk about correlation, never certain causation. Calm, direct tone. Never tell the user to consult or see a doctor or clinician, and add no disclaimers. Do not give medication doses.\n")
        sb.append("2b. Be specific to THIS user: cite their own entries, times, triggers, medication and health numbers, and avoid generic migraine tips they already know.\n")
        sb.append("3. Answer in plain conversational text, concise (usually under 120 words). No JSON. You may wrap key phrases in **double asterisks** for bold; use no other markdown.\n\n")

        if (analysis != null) {
            sb.append("=== INSIGHT SHOWN TO THE USER ===\n")
            if (analysis.headline.isNotBlank()) sb.append("Headline: ").append(analysis.headline).append("\n")
            sb.append("Summary: ").append(analysis.summary).append("\n")
            if (analysis.detailedExplanation.isNotBlank()) sb.append("Details: ").append(analysis.detailedExplanation).append("\n")
            if (analysis.reliefSteps.isNotEmpty()) sb.append("Relief steps: ").append(analysis.reliefSteps.joinToString(" | ")).append("\n")
            if (analysis.triggerPatterns.isNotEmpty()) sb.append("Trigger patterns: ").append(analysis.triggerPatterns.joinToString(" | ")).append("\n")
            if (analysis.precautions.isNotEmpty()) sb.append("Precautions: ").append(analysis.precautions.joinToString(" | ")).append("\n")
            if (analysis.warningBulletPoints.isNotEmpty()) sb.append("Warning signs: ").append(analysis.warningBulletPoints.joinToString(" | ")).append("\n")
            sb.append("\n")
        }
        sb.append(data)
        return sb.toString()
    }
}

/** Medicines added in the Medication screen: [routine] on a schedule, [occasional] taken during attacks. */
data class MedLists(val routine: List<RoutineMed> = emptyList(), val occasional: List<String> = emptyList())
