package app.dim.data.backup

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCodecTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val meta = BackupMeta(1_700_000_000_000, "Asia/Calcutta", "1.0", 0)

    private fun ep(i: Int, ended: Boolean = true) = Episode(
        id = i.toLong(), startedAt = 1_700_000_000_000 + i * 3_600_000L, endedAt = if (ended) 1_700_000_000_000 + i * 3_600_000L + 5_400_000 else null,
        pain = i % 11, stress = 5, symptoms = "Nausea,Aura", triggers = "Stress", medication = "Ibuprofen 400 mg", location = "Left temple,Neck",
        aura = i % 2 == 0, hydration = 1, meal = 2, caffeine = 0, startedInSleep = i % 3 == 0, uid = "uid-$i", onsetPlace = "Home")

    private fun payload(n: Int = 5) = BackupPayload((1..n).map { ep(it) },
        listOf(RoutineMed("Propranolol 40 mg", 480)), listOf("Ibuprofen 400 mg"),
        Profile(name = "A", birthYear = 1990, sex = "Other", cycleTracking = true, diagnosis = "Chronic migraine", firstAge = 14, family = "Mother,Father", notes = "n\nline2 \"q\""), jpeg)

    private fun bytes(p: BackupPayload = payload(), m: BackupMeta = meta) = ByteArrayOutputStream().also { BackupCodec.write(p, m, it) }.toByteArray()
    private fun read(b: ByteArray, mig: BackupMigrations = BackupMigrations()) = BackupCodec.read(ByteArrayInputStream(b), mig)
    private fun kind(b: ByteArray, mig: BackupMigrations = BackupMigrations()) = try { read(b, mig); null } catch (e: BackupException) { e.kind }

    /** Rewrites one zip entry (or drops it when [new] is null). */
    private fun tamper(b: ByteArray, name: String, new: ((ByteArray) -> ByteArray)?): ByteArray {
        val out = ByteArrayOutputStream(); val zo = ZipOutputStream(out)
        ZipInputStream(ByteArrayInputStream(b)).use { zi ->
            while (true) { val e = zi.nextEntry ?: break; val data = zi.readBytes()
                if (e.name == name) { if (new == null) continue; zo.putNextEntry(ZipEntry(name)); zo.write(new(data)) } else { zo.putNextEntry(ZipEntry(e.name)); zo.write(data) }
                zo.closeEntry() } }
        zo.finish(); return out.toByteArray()
    }

    @Test fun `full round trip keeps every field exactly`() {
        val p = payload(); val c = read(bytes(p))
        assertEquals(p.episodes.map { it.copy(id = 0) }, c.payload.episodes)
        assertEquals(p.routine, c.payload.routine); assertEquals(p.occasional, c.payload.occasional)
        assertEquals(p.profile, c.payload.profile); assertArrayEquals(jpeg, c.payload.photo)
        assertEquals(5, c.summary.episodes); assertEquals(meta.createdAt, c.summary.createdAt)
        assertEquals("Asia/Calcutta", c.summary.zoneId); assertTrue(c.summary.hasPhoto)
    }

    @Test fun `timestamps and durations survive exactly`() {
        val e = ep(1).copy(startedAt = 1_700_000_123_457, endedAt = 1_700_004_999_999)
        val back = read(bytes(payload().let { BackupPayload(listOf(e), it.routine, it.occasional, it.profile, null) })).payload.episodes.single()
        assertEquals(e.startedAt, back.startedAt); assertEquals(e.endedAt, back.endedAt)
        assertEquals(e.endedAt!! - e.startedAt, back.endedAt!! - back.startedAt)
    }

    @Test fun `empty app backs up and restores`() {
        val c = read(bytes(BackupPayload(emptyList(), emptyList(), emptyList(), null, null)))
        assertEquals(0, c.summary.episodes); assertFalse(c.summary.hasProfile)
    }

    @Test fun `nullable fields and empty tag lists round trip`() {
        val e = Episode(startedAt = 1000, endedAt = 2000, uid = "x")
        assertEquals(e, read(bytes(BackupPayload(listOf(e), emptyList(), emptyList(), null, null))).payload.episodes.single())
    }

    @Test fun `running episodes in a file are never returned`() {
        val c = read(bytes(BackupPayload(listOf(ep(1), ep(2, ended = false)), emptyList(), emptyList(), null, null)))
        assertEquals(1, c.payload.episodes.size); assertEquals(1, c.summary.ongoingSkipped)
        assertTrue(c.payload.episodes.all { it.endedAt != null })
    }

    @Test fun `garbage, text and empty files are rejected`() {
        assertNotNull(kind(ByteArray(0)))
        assertNotNull(kind("hello, not a zip".toByteArray()))
        assertNotNull(kind(ByteArray(5000) { it.toByte() }))
    }

    @Test fun `truncated zip is rejected`() {
        val b = bytes(); assertNotNull(kind(b.copyOf(b.size / 2)))
    }

    @Test fun `zip without manifest is not a backup`() = assertEquals(BackupException.Kind.NOT_A_BACKUP, kind(tamper(bytes(), BackupFormat.MANIFEST, null)))

    @Test fun `foreign manifest is not a backup`() = assertEquals(BackupException.Kind.NOT_A_BACKUP,
        kind(tamper(bytes(), BackupFormat.MANIFEST) { """{"format":"other"}""".toByteArray() }))

    @Test fun `modified data fails the checksum`() = assertEquals(BackupException.Kind.CORRUPTED,
        kind(tamper(bytes(), BackupFormat.DATA) { String(it).replace("uid-1", "uid-9").toByteArray() }))

    @Test fun `missing data file is incomplete`() = assertEquals(BackupException.Kind.INCOMPLETE, kind(tamper(bytes(), BackupFormat.DATA, null)))
    @Test fun `missing photo listed in manifest is incomplete`() = assertEquals(BackupException.Kind.INCOMPLETE, kind(tamper(bytes(), BackupFormat.PHOTO, null)))

    @Test fun `corrupt manifest json is rejected`() = assertEquals(BackupException.Kind.CORRUPTED, kind(tamper(bytes(), BackupFormat.MANIFEST) { "{oops".toByteArray() }))

    @Test fun `newer schema is refused with an update hint`() {
        val b = tamper(bytes(), BackupFormat.MANIFEST) { JSONObject(String(it)).put("schemaVersion", BackupFormat.SCHEMA_VERSION + 1).toString().toByteArray() }
        assertEquals(BackupException.Kind.UNSUPPORTED_NEWER, kind(b))
    }

    @Test fun `invalid schema version is rejected`() {
        for (v in listOf(0, -3, "x", 1.5)) assertEquals("$v", BackupException.Kind.CORRUPTED,
            kind(tamper(bytes(), BackupFormat.MANIFEST) { JSONObject(String(it)).put("schemaVersion", v).toString().toByteArray() }))
    }

    /** Builds a structurally valid backup around hand-written data.json (so the checksum matches). */
    private fun custom(data: String, version: Int = 1): ByteArray {
        val d = data.toByteArray()
        val manifest = JSONObject().put("format", "dim-backup").put("schemaVersion", version).put("createdAt", 5)
            .put("files", JSONObject().put("data.json", JSONObject().put("sha256", BackupCodec.sha256(d)).put("size", d.size)))
        val out = ByteArrayOutputStream(); val z = ZipOutputStream(out)
        z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString().toByteArray()); z.closeEntry()
        z.putNextEntry(ZipEntry("data.json")); z.write(d); z.closeEntry(); z.finish(); return out.toByteArray()
    }

    @Test fun `older records with missing optional fields and no ids load with defaults`() {
        val c = read(custom("""{"episodes":[{"startedAt":1000,"endedAt":5000,"pain":7,"symptoms":["Nausea"]}]}"""))
        val e = c.payload.episodes.single()
        assertEquals("Nausea", e.symptoms); assertEquals("", e.location); assertFalse(e.aura); assertFalse(e.startedInSleep); assertNull(e.hydration)
        assertEquals(BackupCodec.legacyUid(1000, 5000), e.uid)
        assertEquals(e.uid, read(custom("""{"episodes":[{"startedAt":1000,"endedAt":5000}]}""")).payload.episodes.single().uid)
    }

    @Test fun `missing episode list is incomplete`() = assertEquals(BackupException.Kind.INCOMPLETE, kind(custom("""{"medications":{}}""")))

    @Test fun `invalid records are skipped, valid ones kept, and reported`() {
        val c = read(custom("""{"episodes":[
            {"startedAt":1000,"endedAt":2000},
            {"startedAt":"x","endedAt":2000},
            {"startedAt":3000,"endedAt":2000},
            {"startedAt":1000,"endedAt":2000,"pain":11},
            {"startedAt":1000,"endedAt":2000,"symptoms":["a,b"]},
            {"startedAt":1000,"endedAt":2000,"uid":"../bad"},
            {"startedAt":1000,"endedAt":2000,"aura":"yes"},
            {"startedAt":1000.5,"endedAt":2000},
            42, null]}"""))
        assertEquals(1, c.payload.episodes.size); assertEquals(9, c.summary.invalidRecords); assertTrue(c.summary.warnings.isNotEmpty())
    }

    @Test fun `file where every record is invalid fails instead of restoring nothing`() =
        assertEquals(BackupException.Kind.NO_VALID_DATA, kind(custom("""{"episodes":[{"startedAt":-1,"endedAt":2}, {}]}""")))

    @Test fun `unreadable medicines and profile values are cleaned or dropped`() {
        val c = read(custom("""{"episodes":[],"medications":{"routine":[{"name":"A,B","minutes":60},{"name":"X","minutes":5000},{"name":"A B","minutes":60},{"minutes":3}],
            "occasional":["Y","y",5,""]},"profile":{"birthYear":3000,"name":"N","firstAge":-4}}"""))
        assertEquals(listOf(RoutineMed("A B", 60)), c.payload.routine); assertEquals(listOf("Y"), c.payload.occasional)
        assertNull(c.payload.profile!!.birthYear); assertNull(c.payload.profile!!.firstAge); assertEquals("N", c.payload.profile!!.name)
    }

    @Test fun `damaged photo is skipped with a warning`() {
        val b = tamper(bytes(), BackupFormat.MANIFEST) { m -> // keep the checksum valid for the new photo bytes
            val j = JSONObject(String(m)); val junk = ByteArray(20)
            j.getJSONObject("files").put("profile_photo.jpg", JSONObject().put("sha256", BackupCodec.sha256(junk)).put("size", junk.size)); j.toString().toByteArray() }
        val c = read(tamper(b, BackupFormat.PHOTO) { ByteArray(20) })
        assertNull(c.payload.photo); assertTrue(c.summary.warnings.any { it.contains("photo") }); assertEquals(5, c.summary.episodes)
    }

    @Test fun `older schema is upgraded step by step`() {
        val steps = mapOf<Int, (JSONObject) -> JSONObject>(
            1 to { d -> d.put("v2", true) },
            2 to { d -> val a = d.getJSONArray("episodes"); for (i in 0 until a.length()) a.getJSONObject(i).put("pain", 4); d })
        val mig = BackupMigrations(steps, target = 3)
        val up = mig.upgrade(JSONObject("""{"episodes":[{"startedAt":1}]}"""), 1)
        assertTrue(up.getBoolean("v2")); assertEquals(4, up.getJSONArray("episodes").getJSONObject(0).getInt("pain"))
        assertEquals(BackupException.Kind.UNSUPPORTED_OLD, try { BackupMigrations(emptyMap(), 2).upgrade(JSONObject(), 1); null } catch (e: BackupException) { e.kind })
    }

    @Test fun `oversized entries are refused`() {
        val out = ByteArrayOutputStream(); val z = ZipOutputStream(out)
        z.putNextEntry(ZipEntry("manifest.json")); z.write(ByteArray(BackupFormat.MAX_MANIFEST + 1)); z.closeEntry(); z.finish()
        assertEquals(BackupException.Kind.TOO_LARGE, kind(out.toByteArray()))
    }

    @Test fun `unknown extra files are ignored and never trusted`() {
        val b = bytes(); val out = ByteArrayOutputStream(); val z = ZipOutputStream(out)
        z.putNextEntry(ZipEntry("../../evil.txt")); z.write(1); z.closeEntry(); z.putNextEntry(ZipEntry("future.bin")); z.write(2); z.closeEntry()
        ZipInputStream(ByteArrayInputStream(b)).use { zi -> while (true) { val e = zi.nextEntry ?: break; z.putNextEntry(ZipEntry(e.name)); z.write(zi.readBytes()); z.closeEntry() } }
        z.finish(); assertEquals(5, read(out.toByteArray()).summary.episodes)
    }

    @Test fun `backup contains no unexpected files`() {
        val names = mutableListOf<String>(); ZipInputStream(ByteArrayInputStream(bytes())).use { zi -> while (true) { names += (zi.nextEntry ?: break).name } }
        assertEquals(listOf("manifest.json", "data.json", "profile_photo.jpg"), names)
    }
}
