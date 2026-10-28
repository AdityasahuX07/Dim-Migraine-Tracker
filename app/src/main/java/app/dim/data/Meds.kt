package app.dim.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A medicine taken on a schedule. [minutes] is the time of day as minutes after midnight. */
data class RoutineMed(val name: String, val minutes: Int)

/** Medicine names never contain commas (they are stored comma-separated in Episode.medication), are single-spaced and at most 40 chars. */
fun cleanMedName(s: String) = s.replace(",", " ").trim().replace(Regex("\\s+"), " ").take(40)

/** Medicine lists live on this device only (SharedPreferences). Names never contain commas so they fit Episode.medication. */
class MedStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("dim_meds", Context.MODE_PRIVATE)

    fun routine(): List<RoutineMed> = runCatching {
        val a = JSONArray(prefs.getString("routine", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> RoutineMed(o.getString("n"), o.getInt("m")) } }
    }.getOrDefault(emptyList()).sortedBy { it.minutes }

    fun occasional(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("occasional", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** [sync] writes to disk before returning (restore needs to know it worked); the UI keeps the default async write. */
    fun saveRoutine(l: List<RoutineMed>, sync: Boolean = false) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("m", it.minutes)) }
        prefs.edit().putString("routine", a.toString()).also { if (sync) check(it.commit()) { "Could not save medicines" } else it.apply() }
    }

    fun saveOccasional(l: List<String>, sync: Boolean = false) {
        val a = JSONArray(); l.forEach { a.put(it) }
        prefs.edit().putString("occasional", a.toString()).also { if (sync) check(it.commit()) { "Could not save medicines" } else it.apply() }
    }

    companion object { fun clean(s: String) = cleanMedName(s) }
}
