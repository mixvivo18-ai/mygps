package com.example.mygps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-saved location presets in [SharedPreferences] as a JSON array.
 * Each entry is `{ name, lat, lng }`. SharedPreferences gives us atomic updates
 * without an extra dependency; a real DB would be overkill for this domain.
 */
class SavedLocations(context: Context) {

    companion object {
        const val PREF_NAME = "mygps_saved_locations"
        const val KEY_LOCATIONS = "locations"

        @JvmStatic
        fun isValidName(name: String) = name.isNotBlank() && name.length <= 64

        @JvmStatic
        fun isValidLat(lat: Double) = !lat.isNaN() && lat in -90.0..90.0

        @JvmStatic
        fun isValidLng(lng: Double) = !lng.isNaN() && lng in -180.0..180.0
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    data class SavedLocation(val name: String, val latitude: Double, val longitude: Double) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("lat", latitude)
            .put("lng", longitude)

        companion object {
            fun fromJson(o: JSONObject): SavedLocation? {
                if (!o.has("name") || !o.has("lat") || !o.has("lng")) return null
                val name = o.optString("name", "")
                val lat = o.optDouble("lat", Double.NaN)
                val lng = o.optDouble("lng", Double.NaN)
                if (!isValidName(name) || !isValidLat(lat) || !isValidLng(lng)) return null
                return SavedLocation(name, lat, lng)
            }
        }
    }

    /** All saved locations, in insertion order (oldest first). */
    fun all(): List<SavedLocation> {
        val raw = prefs.getString(KEY_LOCATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    SavedLocation.fromJson(arr.getJSONObject(i))?.let(::add)
                }
            }
        } catch (e: Throwable) {
            // Corrupted blob - nuke it so we don't crash on every read
            prefs.edit().remove(KEY_LOCATIONS).apply()
            emptyList()
        }
    }

    /** Add or replace a location whose name is unique. */
    fun save(name: String, lat: Double, lng: Double): Boolean {
        val cleanName = name.trim()
        if (!isValidName(cleanName) || !isValidLat(lat) || !isValidLng(lng)) return false
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = SavedLocation(cleanName, lat, lng)
        } else {
            current.add(SavedLocation(cleanName, lat, lng))
        }
        return write(current)
    }

    fun delete(name: String): Boolean {
        val current = all().toMutableList()
        val removed = current.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) write(current)
        return removed
    }

    fun clear() {
        prefs.edit().remove(KEY_LOCATIONS).apply()
    }

    private fun write(list: List<SavedLocation>): Boolean {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return prefs.edit().putString(KEY_LOCATIONS, arr.toString()).commit()
    }
}