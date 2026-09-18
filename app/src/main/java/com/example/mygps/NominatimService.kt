package com.example.mygps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight Nominatim (OpenStreetMap) geocoder.
 *
 * Hits https://nominatim.openstreetmap.org/search?format=json&q=...&limit=10
 * Returns the top results with lat/lng/display name.
 *
 * No external dependency (java.net.HttpURLConnection + org.json only).
 */
class NominatimService(
    private val userAgent: String = "mygps/1.0 (Android; developer use)",
    private val endpoint: String = "https://nominatim.openstreetmap.org/search"
) {

    data class Result(
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
        val type: String?
    )

    suspend fun search(query: String, limit: Int = 10): Result? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null

        val urlStr = "$endpoint?format=json&q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit&addressdetails=0"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "nominatim HTTP $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null
            val first = arr.getJSONObject(0)
            Result(
                displayName = first.optString("display_name", q),
                latitude = first.optDouble("lat", Double.NaN),
                longitude = first.optDouble("lon", Double.NaN),
                type = first.optString("type", null)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "nominatim error: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Variant that returns multiple results, for the search dialog list.
     */
    suspend fun searchMulti(query: String, limit: Int = 8): List<Result> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val urlStr = "$endpoint?format=json&q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit&addressdetails=0"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lat = o.optDouble("lat", Double.NaN)
                    val lon = o.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    add(
                        Result(
                            displayName = o.optString("display_name", q),
                            latitude = lat,
                            longitude = lon,
                            type = if (o.has("type") && !o.isNull("type")) o.optString("type") else null
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "nominatim multi error: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "NominatimService"
    }
}