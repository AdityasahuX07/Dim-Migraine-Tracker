package app.dim.data.backup

import app.dim.data.Episode
import app.dim.data.Profile
import app.dim.data.RoutineMed

/** Everything a backup carries. Only finished episodes are included; a running one is runtime state, not history. */
class BackupPayload(
    val episodes: List<Episode>,
    val routine: List<RoutineMed>,
    val occasional: List<String>,
    /** Null when the backup has no profile section. [Profile.photoRev] is device-local and always 0 here. */
    val profile: Profile?,
    /** Square JPEG, or null. */
    val photo: ByteArray?,
)

/** Written to manifest.json. */
class BackupMeta(val createdAt: Long, val zoneId: String, val appVersion: String, val skippedOngoing: Int)

/** What the restore dialog shows. */
class BackupSummary(
    val schemaVersion: Int,
    val createdAt: Long,
    val zoneId: String,
    val appVersion: String,
    val episodes: Int,
    val firstEpisode: Long?,
    val lastEpisode: Long?,
    val routineMeds: Int,
    val occasionalMeds: Int,
    val hasProfile: Boolean,
    val hasPhoto: Boolean,
    /** Records that were unreadable or invalid and will NOT be restored. */
    val invalidRecords: Int,
    /** Records in the file that were still running when saved; never restored. */
    val ongoingSkipped: Int,
    val warnings: List<String>,
)

/** A fully validated backup, ready to restore. */
class RestoreContents(val summary: BackupSummary, val payload: BackupPayload)

enum class RestoreMode { MERGE, REPLACE }

/** [message] is written for the user: what went wrong and what to do about it. */
class BackupException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NOT_A_BACKUP, CORRUPTED, INCOMPLETE, UNSUPPORTED_NEWER, UNSUPPORTED_OLD, TOO_LARGE, NO_VALID_DATA, STORAGE_FULL, IO, PERMISSION, RESTORE_FAILED }
}

class RestoreResult(val inserted: Int, val duplicatesSkipped: Int, val replaced: Boolean, val ongoingKept: Boolean)
