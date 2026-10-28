package app.dim.data.backup

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed

class EpisodePlan(val toInsert: List<Episode>, val replaceFinished: Boolean, val duplicates: Int)

/** Pure decisions for Merge / Replace, kept free of Android and the database so they can be unit tested. */
object BackupMerge {
    /**
     * [existing] is everything on the device right now (read inside the restore transaction).
     * - Merge keeps all of it; Replace keeps only a running episode (finished ones are deleted by the caller).
     * - A backup record is a duplicate when its uid matches, when it has the same start and end as a kept one,
     *   or when it starts exactly when the running episode started. Duplicates inside the file collapse too.
     * - Running episodes in the backup are never inserted, and nothing is ever written over the running episode.
     */
    fun planEpisodes(existing: List<Episode>, incoming: List<Episode>, mode: RestoreMode): EpisodePlan {
        val kept = if (mode == RestoreMode.REPLACE) existing.filter { it.endedAt == null } else existing
        val uids = kept.mapTo(HashSet()) { it.uid }
        val spans = kept.mapTo(HashSet()) { it.startedAt to it.endedAt }
        val runningStarts = kept.filter { it.endedAt == null }.mapTo(HashSet()) { it.startedAt }
        val insert = ArrayList<Episode>(incoming.size)
        var dup = 0
        for (e in incoming) {
            if (e.endedAt == null) continue
            if (e.uid in uids || (e.startedAt to e.endedAt) in spans || e.startedAt in runningStarts) { dup++; continue }
            uids += e.uid; spans += e.startedAt to e.endedAt
            insert += e.copy(id = 0)
        }
        return EpisodePlan(insert, mode == RestoreMode.REPLACE, dup)
    }

    /** Keeps everything already on the device and adds medicines it doesn't have yet (same name and time = same medicine). */
    fun mergeRoutine(local: List<RoutineMed>, incoming: List<RoutineMed>): List<RoutineMed> {
        val seen = local.mapTo(HashSet()) { it.name.lowercase() to it.minutes }
        return local + incoming.filter { seen.add(it.name.lowercase() to it.minutes) }
    }

    fun mergeOccasional(local: List<String>, incoming: List<String>): List<String> {
        val seen = local.mapTo(HashSet()) { it.lowercase() }
        return local + incoming.filter { seen.add(it.lowercase()) }
    }

    /** Fills only what is still empty on this device; anything already entered here wins. [Profile.photoRev] stays local. */
    fun mergeProfile(local: Profile, incoming: Profile) = local.copy(
        name = local.name.ifBlank { incoming.name },
        birthYear = local.birthYear ?: incoming.birthYear,
        sex = local.sex.ifBlank { incoming.sex },
        cycleTracking = local.cycleTracking || (local.sex.isBlank() && incoming.cycleTracking),
        diagnosis = local.diagnosis.ifBlank { incoming.diagnosis },
        aura = local.aura.ifBlank { incoming.aura },
        firstAge = local.firstAge ?: incoming.firstAge,
        family = local.family.ifBlank { incoming.family },
        conditions = local.conditions.ifBlank { incoming.conditions },
        notes = local.notes.ifBlank { incoming.notes },
    )
}
