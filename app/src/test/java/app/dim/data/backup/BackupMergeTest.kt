package app.dim.data.backup

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import org.junit.Assert.*
import org.junit.Test

class BackupMergeTest {
    private fun fin(i: Int, uid: String = "u$i") = Episode(id = i.toLong(), startedAt = i * 10_000L, endedAt = i * 10_000L + 5_000, uid = uid)
    private fun running(i: Int) = Episode(id = i.toLong(), startedAt = i * 10_000L, endedAt = null, uid = "run$i")

    @Test fun `merge adds only new records and keeps all existing`() {
        val p = BackupMerge.planEpisodes(listOf(fin(1), fin(2)), listOf(fin(2), fin(3)), RestoreMode.MERGE)
        assertEquals(listOf("u3"), p.toInsert.map { it.uid }); assertEquals(1, p.duplicates); assertFalse(p.replaceFinished)
        assertTrue(p.toInsert.all { it.id == 0L })
    }

    @Test fun `restoring the same backup repeatedly never duplicates`() {
        val backup = (1..4).map { fin(it) }
        var db = listOf(fin(9))
        repeat(3) { val p = BackupMerge.planEpisodes(db, backup, RestoreMode.MERGE); db = db + p.toInsert.mapIndexed { i, e -> e.copy(id = 100L + db.size + i) } }
        assertEquals(5, db.size); assertEquals(5, db.map { it.uid }.toSet().size)
    }

    @Test fun `same start and end counts as duplicate even with a different uid`() {
        val p = BackupMerge.planEpisodes(listOf(fin(1)), listOf(fin(1, uid = "other")), RestoreMode.MERGE)
        assertTrue(p.toInsert.isEmpty()); assertEquals(1, p.duplicates)
    }

    @Test fun `duplicates inside the file collapse`() {
        val p = BackupMerge.planEpisodes(emptyList(), listOf(fin(1), fin(1), fin(1, "z")), RestoreMode.MERGE)
        assertEquals(1, p.toInsert.size)
    }

    @Test fun `replace deletes finished data but plans nothing against the running episode`() {
        val p = BackupMerge.planEpisodes(listOf(fin(1), running(7)), listOf(fin(1), fin(2)), RestoreMode.REPLACE)
        assertTrue(p.replaceFinished); assertEquals(listOf("u1", "u2"), p.toInsert.map { it.uid })   // fin(1) is re-added because replace deletes it first
    }

    @Test fun `a running episode is never overwritten or duplicated by a backup record`() {
        val clash = fin(7)   // starts exactly when the running episode started
        for (m in RestoreMode.values()) {
            val p = BackupMerge.planEpisodes(listOf(running(7)), listOf(clash, fin(8)), m)
            assertEquals(listOf("u8"), p.toInsert.map { it.uid })
        }
    }

    @Test fun `running episodes from a backup are never inserted`() {
        assertTrue(BackupMerge.planEpisodes(emptyList(), listOf(running(1)), RestoreMode.REPLACE).toInsert.isEmpty())
    }

    @Test fun `merge never changes a stored uid`() {
        assertEquals("keep-me", BackupMerge.planEpisodes(emptyList(), listOf(fin(1, "keep-me")), RestoreMode.MERGE).toInsert.single().uid)
    }

    @Test fun `medicine lists merge without duplicates`() {
        val r = BackupMerge.mergeRoutine(listOf(RoutineMed("A", 60)), listOf(RoutineMed("a", 60), RoutineMed("A", 90)))
        assertEquals(listOf(RoutineMed("A", 60), RoutineMed("A", 90)), r)
        assertEquals(listOf("X", "Y"), BackupMerge.mergeOccasional(listOf("X"), listOf("x", "Y")))
    }

    @Test fun `profile merge fills blanks and never overwrites local entries`() {
        val local = Profile(photoRev = 5, name = "Local", diagnosis = "", notes = "mine")
        val inc = Profile(name = "Other", birthYear = 1980, diagnosis = "Chronic migraine", notes = "theirs", firstAge = 12)
        val m = BackupMerge.mergeProfile(local, inc)
        assertEquals("Local", m.name); assertEquals("mine", m.notes); assertEquals("Chronic migraine", m.diagnosis)
        assertEquals(1980, m.birthYear); assertEquals(12, m.firstAge); assertEquals(5L, m.photoRev)
    }
}
