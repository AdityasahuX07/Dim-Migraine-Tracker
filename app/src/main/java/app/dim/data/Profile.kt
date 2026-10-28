package app.dim.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/** Everything is optional and stays on this device (SharedPreferences + one small JPEG in app storage). */
data class Profile(
    // Personal information
    val photoRev: Long = 0,            // 0 = no photo; otherwise the time it was saved (used to refresh the image)
    val name: String = "",
    val birthYear: Int? = null,        // age is derived from this, never stored
    val sex: String = "",              // "Female" | "Male" | "Other" | ""
    val cycleTracking: Boolean = false, // only meaningful when sex == "Female"
    // Migraine information
    val diagnosis: String = "",
    val aura: String = "",
    val firstAge: Int? = null,         // age at first migraine
    val family: String = "",           // comma-separated
    val conditions: String = "",       // comma-separated
    val notes: String = "",
)

class ProfileStore(private val ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("dim_profile", Context.MODE_PRIVATE)

    fun load() = Profile(
        photoRev = prefs.getLong("photoRev", 0),
        name = prefs.getString("name", "") ?: "",
        birthYear = prefs.getInt("birthYear", 0).takeIf { it > 0 },
        sex = prefs.getString("sex", "") ?: "",
        cycleTracking = prefs.getBoolean("cycleTracking", false),
        diagnosis = prefs.getString("diagnosis", "") ?: "",
        aura = prefs.getString("aura", "") ?: "",
        firstAge = prefs.getInt("firstAge", -1).takeIf { it >= 0 },
        family = prefs.getString("family", "") ?: "",
        conditions = prefs.getString("conditions", "") ?: "",
        notes = prefs.getString("notes", "") ?: "",
    )

    /** [sync] writes to disk before returning (restore needs to know it worked); the UI keeps the default async write. */
    fun save(p: Profile, sync: Boolean = false) {
        val e = prefs.edit()
            .putLong("photoRev", p.photoRev).putString("name", p.name).putInt("birthYear", p.birthYear ?: 0)
            .putString("sex", p.sex).putBoolean("cycleTracking", p.cycleTracking)
            .putString("diagnosis", p.diagnosis).putString("aura", p.aura).putInt("firstAge", p.firstAge ?: -1)
            .putString("family", p.family).putString("conditions", p.conditions).putString("notes", p.notes)
        if (sync) check(e.commit()) { "Could not save profile" } else e.apply()
    }

    fun photoFile() = File(ctx.applicationContext.filesDir, "profile_photo.jpg")

    /** Centre-crops the picked image to a 512 px square JPEG in app storage. Returns false if it could not be read. */
    fun savePhoto(uri: Uri): Boolean {
        return try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 768 && bounds.outHeight / (sample * 2) >= 768) sample *= 2
            val raw = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
            val degrees = cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val upright = if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
            val side = minOf(upright.width, upright.height)
            val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(square, 512, 512, true)
            photoFile().outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        } catch (e: Exception) { false }
    }

    fun deletePhoto() { photoFile().delete() }

    /** Restore: writes already-prepared JPEG bytes next to the real file, then renames, so a failure never leaves a half-written photo. */
    fun writePhotoBytes(bytes: ByteArray) {
        val tmp = File(photoFile().parentFile, "profile_photo.jpg.tmp")
        try {
            tmp.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
            check(tmp.renameTo(photoFile())) { "Could not save photo" }
        } finally { tmp.delete() }
    }
}
