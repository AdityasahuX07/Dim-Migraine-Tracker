package app.dim.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.dim.data.MedStore
import app.dim.data.ProfileStore
import app.dim.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId

/**
 * Backup and restore on top of the app's own Repo, MedStore and ProfileStore.
 * Files are only ever read or written through URIs the user picked (Storage Access Framework); no storage permission is used.
 */
class BackupManager(ctx: Context, private val repo: Repo) {
    private val ctx = ctx.applicationContext
    private val state = this.ctx.getSharedPreferences("dim_backup", Context.MODE_PRIVATE)   // not part of backups

    fun lastBackupAt(): Long? = state.getLong("lastBackupAt", 0).takeIf { it > 0 }

    /** Writes a backup to [uri] (a document the user just created). On any failure or cancel the document is removed again. */
    suspend fun createBackup(uri: Uri, now: Long = System.currentTimeMillis()): BackupSummary = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "backup-$now.tmp")
        try {
            val all = repo.snapshot()
            val finished = all.filter { it.endedAt != null }
            val profiles = ProfileStore(ctx); val meds = MedStore(ctx)
            val photo = profiles.photoFile().takeIf { it.exists() }?.readBytes()
            val payload = BackupPayload(finished, meds.routine(), meds.occasional(),
                profiles.load().copy(photoRev = 0), photo)
            val meta = BackupMeta(now, ZoneId.systemDefault().id, appVersion(), all.size - finished.size)
            currentCoroutineContext().ensureActive()

            // 1. Build and verify locally, so nothing unusable is ever put at the destination.
            tmp.outputStream().buffered().use { BackupCodec.write(payload, meta, it) }
            val built = tmp.inputStream().buffered().use { BackupCodec.read(it) }
            check(built.summary.episodes == finished.size) { "Verification failed" }
            currentCoroutineContext().ensureActive()

            // 2. Copy to the chosen location, then read it back from there.
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) }; o.flush() }
            currentCoroutineContext().ensureActive()
            val written = ctx.contentResolver.openInputStream(uri)?.buffered()?.use { BackupCodec.read(it) }
                ?: throw IOException("Cannot read back destination")
            check(written.summary.episodes == finished.size) { "Verification failed" }

            state.edit().putLong("lastBackupAt", now).commit()
            written.summary
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) } }
            if (t is CancellationException) throw t
            throw map(t, "The backup couldn't be created.")
        } finally { tmp.delete() }
    }

    /** Reads and validates the file at [uri] without changing anything. */
    suspend fun inspect(uri: Uri): RestoreContents = withContext(Dispatchers.IO) {
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            input.buffered().use { BackupCodec.read(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw map(t, "The file couldn't be read.")
        }
    }

    /**
     * All-or-nothing: the database changes and the settings/photo changes happen inside one Room transaction, and if anything
     * fails (or the coroutine is cancelled) the transaction rolls back and the previous settings and photo are put back.
     * A running episode is never modified, deleted or started: backups never contain one as active, and Replace skips it.
     */
    suspend fun restore(c: RestoreContents, mode: RestoreMode): RestoreResult = withContext(Dispatchers.IO) {
        val profiles = ProfileStore(ctx); val meds = MedStore(ctx)
        val oldProfile = profiles.load(); val oldRoutine = meds.routine(); val oldOccasional = meds.occasional()
        val photoFile = profiles.photoFile()
        val oldPhoto = photoFile.takeIf { it.exists() }?.readBytes()
        var touched = false
        try {
            repo.transaction {
                val existing = allNow()
                val plan = BackupMerge.planEpisodes(existing, c.payload.episodes, mode)
                if (plan.replaceFinished) deleteFinished()
                if (plan.toInsert.isNotEmpty()) check(insertAll(plan.toInsert).none { it == -1L }) { "Duplicate record" }
                currentCoroutineContext().ensureActive()

                touched = true
                val p = c.payload
                val now = System.currentTimeMillis()
                if (mode == RestoreMode.REPLACE) {
                    meds.saveRoutine(p.routine, sync = true)
                    meds.saveOccasional(p.occasional, sync = true)
                } else {
                    meds.saveRoutine(BackupMerge.mergeRoutine(oldRoutine, p.routine), sync = true)
                    meds.saveOccasional(BackupMerge.mergeOccasional(oldOccasional, p.occasional), sync = true)
                }
                p.profile?.let { inc ->
                    val hadPhoto = oldProfile.photoRev != 0L && oldPhoto != null
                    var rev = oldProfile.photoRev
                    if (mode == RestoreMode.REPLACE) {
                        if (p.photo != null) { profiles.writePhotoBytes(p.photo); rev = now } else { profiles.deletePhoto(); rev = 0 }
                        profiles.save(inc.copy(photoRev = rev), sync = true)
                    } else {
                        if (p.photo != null && !hadPhoto) { profiles.writePhotoBytes(p.photo); rev = now }
                        profiles.save(BackupMerge.mergeProfile(oldProfile, inc).copy(photoRev = rev), sync = true)
                    }
                }
                currentCoroutineContext().ensureActive()
                RestoreResult(plan.toInsert.size, plan.duplicates, mode == RestoreMode.REPLACE, existing.any { it.endedAt == null })
            }
        } catch (t: Throwable) {
            if (touched) withContext(NonCancellable) {
                runCatching { meds.saveRoutine(oldRoutine, sync = true) }
                runCatching { meds.saveOccasional(oldOccasional, sync = true) }
                runCatching { if (oldPhoto != null) profiles.writePhotoBytes(oldPhoto) else profiles.deletePhoto() }
                runCatching { profiles.save(oldProfile, sync = true) }
            }
            if (t is CancellationException) throw t
            throw map(t, "Restore failed. Your existing data was not changed.", BackupException.Kind.RESTORE_FAILED)
        }
    }

    private fun appVersion(): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

    private fun map(t: Throwable, fallback: String, kind: BackupException.Kind = BackupException.Kind.IO): BackupException = when {
        t is BackupException -> t
        t is SecurityException -> BackupException(BackupException.Kind.PERMISSION, "Access to that location was lost. Choose the file or folder again.", t)
        t is IOException && (t.message.orEmpty().contains("ENOSPC") || t.message.orEmpty().contains("No space", true)) ->
            BackupException(BackupException.Kind.STORAGE_FULL, "Not enough storage space. Free up some space or choose another location, then try again.", t)
        t is IOException -> BackupException(BackupException.Kind.IO, "$fallback Check that the location is available (and online, for cloud storage), then try again.", t)
        else -> BackupException(kind, fallback, t)
    }
}
