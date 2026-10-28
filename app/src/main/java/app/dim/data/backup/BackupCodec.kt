package app.dim.data.backup

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed
import app.dim.data.cleanMedName
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupFormat {
    const val FORMAT = "dim-backup"
    /** Bump when data.json changes shape, and add a step to [BackupMigrations] that upgrades the previous version. */
    const val SCHEMA_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"
    const val PHOTO = "profile_photo.jpg"
    const val MAX_ENTRIES = 16
    const val MAX_MANIFEST = 64 * 1024
    const val MAX_DATA = 32 * 1024 * 1024
    const val MAX_PHOTO = 5 * 1024 * 1024
    const val MAX_EPISODES = 200_000
    const val MAX_MEDS = 500
    internal const val MAX_TAGS = 50
    internal const val MAX_TAG_LEN = 100
    internal const val MAX_TEXT = 5_000
    internal const val MAX_YEAR_MILLIS = 4_102_444_800_000L   // 1 Jan 2100: anything later is garbage
}

/**
 * Upgrades the parsed data.json of an older backup, one version at a time: steps[v] turns schema v into v + 1.
 * Schema 1 is the first version, so no steps exist yet; [steps] is injectable so the chain can be tested.
 */
class BackupMigrations(private val steps: Map<Int, (JSONObject) -> JSONObject> = emptyMap(), private val target: Int = BackupFormat.SCHEMA_VERSION) {
    fun upgrade(data: JSONObject, from: Int): JSONObject {
        var cur = data
        for (v in from until target) {
            val step = steps[v] ?: throw BackupException(BackupException.Kind.UNSUPPORTED_OLD,
                "This backup was made by a much older version of the app and can't be restored. Open it in that version, or create a new backup there first.")
            cur = step(cur)
        }
        return cur
    }
}

/**
 * Backup file = a zip with:
 *  - manifest.json  format, schema version, creation time, counts and a SHA-256 + size of every other file
 *  - data.json      episodes, medicine lists, profile (plain, unencrypted JSON)
 *  - profile_photo.jpg (optional)
 * Pure JVM (no Android classes besides org.json) so it can be unit tested.
 */
object BackupCodec {
    // ------------------------------------------------------------------ write

    fun write(payload: BackupPayload, meta: BackupMeta, out: OutputStream) {
        val data = JSONObject()
            .put("episodes", JSONArray().also { a -> payload.episodes.forEach { a.put(episodeJson(it)) } })
            .put("medications", JSONObject()
                .put("routine", JSONArray().also { a -> payload.routine.forEach { a.put(JSONObject().put("name", it.name).put("minutes", it.minutes)) } })
                .put("occasional", JSONArray().also { a -> payload.occasional.forEach { a.put(it) } }))
        payload.profile?.let { data.put("profile", profileJson(it)) }
        val dataBytes = data.toString().toByteArray(Charsets.UTF_8)

        val files = JSONObject().put(BackupFormat.DATA, fileInfo(dataBytes))
        payload.photo?.let { files.put(BackupFormat.PHOTO, fileInfo(it)) }
        val first = payload.episodes.minOfOrNull { it.startedAt }
        val last = payload.episodes.maxOfOrNull { it.startedAt }
        val manifest = JSONObject()
            .put("format", BackupFormat.FORMAT)
            .put("schemaVersion", BackupFormat.SCHEMA_VERSION)
            .put("createdAt", meta.createdAt)
            .put("timeZone", meta.zoneId)
            .put("appVersion", meta.appVersion)
            .put("encrypted", false)
            .put("counts", JSONObject()
                .put("episodes", payload.episodes.size)
                .put("routineMedications", payload.routine.size)
                .put("occasionalMedications", payload.occasional.size)
                .put("hasProfile", payload.profile != null)
                .put("hasPhoto", payload.photo != null)
                .put("firstEpisodeStartedAt", first ?: JSONObject.NULL)
                .put("lastEpisodeStartedAt", last ?: JSONObject.NULL)
                .put("ongoingEpisodesNotIncluded", meta.skippedOngoing))
            .put("files", files)

        val zip = ZipOutputStream(out)
        fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        entry(BackupFormat.MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))
        entry(BackupFormat.DATA, dataBytes)
        payload.photo?.let { entry(BackupFormat.PHOTO, it) }
        zip.finish()
        zip.flush()
    }

    private fun episodeJson(e: Episode) = JSONObject()
        .put("uid", e.uid)
        .put("startedAt", e.startedAt)
        .put("endedAt", e.endedAt ?: JSONObject.NULL)
        .put("pain", e.pain ?: JSONObject.NULL)
        .put("stress", e.stress ?: JSONObject.NULL)
        .put("symptoms", tags(e.symptoms))
        .put("triggers", tags(e.triggers))
        .put("medication", tags(e.medication))
        .put("location", tags(e.location))
        .put("aura", e.aura)
        .put("hydration", e.hydration ?: JSONObject.NULL)
        .put("meal", e.meal ?: JSONObject.NULL)
        .put("caffeine", e.caffeine ?: JSONObject.NULL)
        .put("startedInSleep", e.startedInSleep)
        .put("notes", e.notes)
        .put("onsetPlace", e.onsetPlace)

    private fun tags(csv: String) = JSONArray().also { a -> csv.split(",").filter { it.isNotBlank() }.forEach { a.put(it) } }

    private fun profileJson(p: Profile) = JSONObject()
        .put("name", p.name).put("birthYear", p.birthYear ?: JSONObject.NULL).put("sex", p.sex)
        .put("cycleTracking", p.cycleTracking).put("diagnosis", p.diagnosis).put("aura", p.aura)
        .put("firstAge", p.firstAge ?: JSONObject.NULL).put("family", p.family).put("conditions", p.conditions).put("notes", p.notes)

    private fun fileInfo(b: ByteArray) = JSONObject().put("sha256", sha256(b)).put("size", b.size)

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ read

    /** Reads, verifies and validates a backup. Throws [BackupException] (with a user-readable message) for anything wrong with the file. */
    fun read(input: InputStream, migrations: BackupMigrations = BackupMigrations()): RestoreContents {
        val entries = readEntries(input)
        val manifestBytes = entries[BackupFormat.MANIFEST]
            ?: throw BackupException(BackupException.Kind.NOT_A_BACKUP, "This file isn't a Dim backup. Choose a backup file that was created with Create Backup.")
        val manifest = try { JSONObject(String(manifestBytes, Charsets.UTF_8)) } catch (e: JSONException) {
            throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (its header can't be read). Try another copy of the file.", e) }
        if (manifest.optString("format") != BackupFormat.FORMAT)
            throw BackupException(BackupException.Kind.NOT_A_BACKUP, "This file isn't a Dim backup. Choose a backup file that was created with Create Backup.")

        val version = (manifest.opt("schemaVersion") as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toInt()
        if (version == null || version < 1) throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (unknown version). Try another copy of the file.")
        if (version > BackupFormat.SCHEMA_VERSION) throw BackupException(BackupException.Kind.UNSUPPORTED_NEWER,
            "This backup was made by a newer version of the app. Update the app, then try again.")
        if (manifest.optBoolean("encrypted", false)) throw BackupException(BackupException.Kind.UNSUPPORTED_NEWER,
            "This backup is encrypted, which this version of the app can't open. Update the app, then try again.")

        // Every file the manifest lists must be present, the right size and match its hash.
        val files = manifest.optJSONObject("files") ?: throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (no file list). Try another copy of the file.")
        for (name in listOf(BackupFormat.DATA, BackupFormat.PHOTO)) {
            val info = files.optJSONObject(name)
            if (info == null) { if (name == BackupFormat.DATA) throw BackupException(BackupException.Kind.INCOMPLETE, "The backup is incomplete (no data). Try another copy of the file."); continue }
            val bytes = entries[name] ?: throw BackupException(BackupException.Kind.INCOMPLETE, "The backup is incomplete ($name is missing). Try another copy of the file.")
            if (info.optLong("size", -1) != bytes.size.toLong() || !info.optString("sha256").equals(sha256(bytes), ignoreCase = true))
                throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (its contents don't match their checksum). Try another copy of the file.")
        }

        val rawData = try { JSONObject(String(entries.getValue(BackupFormat.DATA), Charsets.UTF_8)) } catch (e: JSONException) {
            throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (its data can't be read). Try another copy of the file.", e) }
        val data = migrations.upgrade(rawData, version)

        val warnings = mutableListOf<String>()
        val episodesJson = data.optJSONArray("episodes")
            ?: throw BackupException(BackupException.Kind.INCOMPLETE, "The backup is incomplete (no episode list). Try another copy of the file.")
        if (episodesJson.length() > BackupFormat.MAX_EPISODES) throw BackupException(BackupException.Kind.TOO_LARGE, "This backup has too many records to restore.")

        val episodes = ArrayList<Episode>(episodesJson.length())
        var invalid = 0; var ongoing = 0
        for (i in 0 until episodesJson.length()) {
            val o = episodesJson.optJSONObject(i)
            if (o == null) { invalid++; continue }
            try {
                val e = parseEpisode(o)
                if (e == null) ongoing++ else episodes += e
            } catch (ex: IllegalArgumentException) { invalid++ }
        }
        if (episodesJson.length() > 0 && episodes.isEmpty() && ongoing == 0)
            throw BackupException(BackupException.Kind.NO_VALID_DATA, "None of the records in this backup could be read, so nothing was restored.")
        if (invalid > 0) warnings += "$invalid ${plural(invalid, "record")} can't be read and will be skipped."
        if (ongoing > 0) warnings += "$ongoing unfinished ${plural(ongoing, "episode")} in the file will be skipped."

        val meds = data.optJSONObject("medications")
        val routine = parseRoutine(meds?.optJSONArray("routine"))
        val occasional = parseOccasional(meds?.optJSONArray("occasional"))
        val profile = data.optJSONObject("profile")?.let { parseProfile(it) }
        var photo = entries[BackupFormat.PHOTO]?.takeIf { files.optJSONObject(BackupFormat.PHOTO) != null }
        if (photo != null && !isJpeg(photo)) { photo = null; warnings += "The profile photo is damaged and will be skipped." }

        val summary = BackupSummary(
            schemaVersion = version,
            createdAt = (manifest.opt("createdAt") as? Number)?.toLong() ?: 0L,
            zoneId = manifest.optString("timeZone", ""),
            appVersion = manifest.optString("appVersion", ""),
            episodes = episodes.size,
            firstEpisode = episodes.minOfOrNull { it.startedAt },
            lastEpisode = episodes.maxOfOrNull { it.startedAt },
            routineMeds = routine.size, occasionalMeds = occasional.size,
            hasProfile = profile != null, hasPhoto = photo != null,
            invalidRecords = invalid, ongoingSkipped = ongoing, warnings = warnings)
        return RestoreContents(summary, BackupPayload(episodes, routine, occasional, profile, photo))
    }

    /** Reads the zip fully into memory, with hard size limits (a backup is small; a zip bomb must not exhaust memory). */
    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (result.size >= BackupFormat.MAX_ENTRIES) throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (too many files). Try another copy of the file.")
                    if (e.isDirectory) continue
                    if (result.containsKey(e.name)) throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged (duplicate files). Try another copy of the file.")
                    val max = when (e.name) { BackupFormat.MANIFEST -> BackupFormat.MAX_MANIFEST; BackupFormat.DATA -> BackupFormat.MAX_DATA; BackupFormat.PHOTO -> BackupFormat.MAX_PHOTO; else -> 0 }
                    if (max == 0) continue   // unknown entry (from a future version): ignored, never extracted anywhere
                    result[e.name] = readLimited(zin, max)
                }
            }
        } catch (e: ZipException) {
            throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged or incomplete (it may have been interrupted while saving). Try another copy of the file.", e)
        } catch (e: EOFException) {
            throw BackupException(BackupException.Kind.CORRUPTED, "The backup is damaged or incomplete (it may have been interrupted while saving). Try another copy of the file.", e)
        }
        if (result.isEmpty()) throw BackupException(BackupException.Kind.NOT_A_BACKUP, "This file isn't a Dim backup. Choose a backup file that was created with Create Backup.")
        return result
    }

    private fun readLimited(zin: ZipInputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zin.read(buf); if (n < 0) break
            if (out.size() + n > max) throw BackupException(BackupException.Kind.TOO_LARGE, "This backup is larger than expected and can't be restored.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun isJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun plural(n: Int, w: String) = if (n == 1) w else w + "s"

    // ------------------------------------------------------------------ record parsing (lenient about missing optional fields, strict about wrong ones)

    /** Null = a still-running episode (valid, but never restored). Throws IllegalArgumentException for an invalid record. */
    private fun parseEpisode(o: JSONObject): Episode? {
        val started = o.int64("startedAt", required = true)!!
        require(started in 0..BackupFormat.MAX_YEAR_MILLIS) { "startedAt out of range" }
        val ended = o.int64("endedAt", required = false)
        if (ended == null) return null
        require(ended in started..BackupFormat.MAX_YEAR_MILLIS) { "endedAt before startedAt" }
        val uid = if (o.has("uid") && !o.isNull("uid")) {
            val u = o.opt("uid"); require(u is String && UID.matches(u)) { "bad uid" }; u
        } else legacyUid(started, ended)   // old / foreign files without ids: the same record always gets the same id
        return Episode(
            id = 0, startedAt = started, endedAt = ended,
            pain = o.range("pain", 0, 10), stress = o.range("stress", 0, 10),
            symptoms = o.tags("symptoms"), triggers = o.tags("triggers"), medication = o.tags("medication"), location = o.tags("location"),
            aura = o.flag("aura"), hydration = o.range("hydration", 0, 2), meal = o.range("meal", 0, 2), caffeine = o.range("caffeine", 0, 2),
            startedInSleep = o.flag("startedInSleep"), uid = uid, notes = o.optString("notes", "").take(500),
            onsetPlace = o.optString("onsetPlace", "").take(40))
    }

    private val UID = Regex("[A-Za-z0-9_-]{1,64}")

    fun legacyUid(startedAt: Long, endedAt: Long?) = "legacy-" + sha256("$startedAt|${endedAt ?: ""}".toByteArray()).take(32)

    private fun parseRoutine(a: JSONArray?): List<RoutineMed> {
        if (a == null) return emptyList()
        val seen = HashSet<Pair<String, Int>>(); val out = ArrayList<RoutineMed>()
        for (i in 0 until minOf(a.length(), BackupFormat.MAX_MEDS)) {
            val o = a.optJSONObject(i) ?: continue
            val name = cleanMedName((o.opt("name") as? String) ?: continue)
            val minutes = (o.opt("minutes") as? Number)?.toInt() ?: continue
            if (name.isEmpty() || minutes !in 0..1439) continue
            if (seen.add(name.lowercase() to minutes)) out += RoutineMed(name, minutes)
        }
        return out
    }

    private fun parseOccasional(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (i in 0 until minOf(a.length(), BackupFormat.MAX_MEDS)) {
            val name = cleanMedName((a.opt(i) as? String) ?: continue)
            if (name.isNotEmpty() && seen.add(name.lowercase())) out += name
        }
        return out
    }

    private fun parseProfile(o: JSONObject): Profile {
        fun s(k: String, max: Int = 200) = (o.opt(k) as? String)?.take(max) ?: ""
        return Profile(
            photoRev = 0, name = s("name"), birthYear = runCatching { o.range("birthYear", 1900, 2100) }.getOrNull(),
            sex = s("sex"), cycleTracking = runCatching { o.flag("cycleTracking") }.getOrDefault(false), diagnosis = s("diagnosis"), aura = s("aura"),
            firstAge = runCatching { o.range("firstAge", 0, 120) }.getOrNull(), family = s("family"), conditions = s("conditions"), notes = s("notes", BackupFormat.MAX_TEXT))
    }

    /** Integer field: absent / null -> null; present but not a whole number -> invalid record. */
    private fun JSONObject.int64(k: String, required: Boolean): Long? {
        val v = opt(k)
        if (v == null || v == JSONObject.NULL) { require(!required) { "$k missing" }; return null }
        require(v is Number && v.toDouble() == v.toLong().toDouble()) { "$k not an integer" }
        return v.toLong()
    }

    private fun JSONObject.range(k: String, lo: Int, hi: Int): Int? {
        val v = int64(k, false) ?: return null
        require(v in lo..hi) { "$k out of range" }
        return v.toInt()
    }

    private fun JSONObject.flag(k: String): Boolean {
        val v = opt(k)
        if (v == null || v == JSONObject.NULL) return false
        require(v is Boolean) { "$k not a boolean" }
        return v
    }

    /** Array of short strings without commas (the app stores them comma-separated) -> the app's comma-separated string. */
    private fun JSONObject.tags(k: String): String {
        val v = opt(k)
        if (v == null || v == JSONObject.NULL) return ""
        require(v is JSONArray && v.length() <= BackupFormat.MAX_TAGS) { "$k not a list" }
        return (0 until v.length()).map { i ->
            val t = v.opt(i); require(t is String && t.length <= BackupFormat.MAX_TAG_LEN && !t.contains(',')) { "$k has a bad item" }; t
        }.filter { it.isNotBlank() }.joinToString(",")
    }
}
