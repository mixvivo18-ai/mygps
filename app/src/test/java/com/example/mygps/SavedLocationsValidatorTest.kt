package com.example.mygps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [SavedLocations] validators and JSON round-trip.
 *
 * Uses `org.json` which is bundled into Android and also available on JVM via the
 * standalone artifact `org.json:json`. On a normal Gradle build, `org.json` is
 * already on the test classpath because it ships with the Android stub jar.
 */
class SavedLocationsValidatorTest {

    @Test
    fun name_valid_when_non_blank_and_short() {
        assertTrue(SavedLocations.isValidName("Bangkok"))
        assertTrue(SavedLocations.isValidName("NYC"))
        assertTrue(SavedLocations.isValidName("A"))
    }

    @Test
    fun name_invalid_when_blank_or_too_long() {
        assertFalse(SavedLocations.isValidName(""))
        assertFalse(SavedLocations.isValidName("   "))
        assertFalse(SavedLocations.isValidName("a".repeat(65)))
    }

    @Test
    fun lat_lng_validators_match_engine() {
        assertTrue(SavedLocations.isValidLat(13.7))
        assertFalse(SavedLocations.isValidLat(91.0))
        assertTrue(SavedLocations.isValidLng(100.5))
        assertFalse(SavedLocations.isValidLng(181.0))
    }

    @Test
    fun saved_location_to_json_contains_all_fields() {
        val s = SavedLocations.SavedLocation("Tokyo", 35.6762, 139.6503)
        val j = s.toJson()
        assertEquals("Tokyo", j.getString("name"))
        assertEquals(35.6762, j.getDouble("lat"), 1e-9)
        assertEquals(139.6503, j.getDouble("lng"), 1e-9)
    }

    @Test
    fun saved_location_from_json_round_trip() {
        val original = SavedLocations.SavedLocation("Bangkok", 13.7563, 100.5018)
        val json = original.toJson().toString()
        val parsed = SavedLocations.SavedLocation.fromJson(JSONObject(json))
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun from_json_returns_null_when_fields_missing() {
        val missing = JSONObject().put("name", "X")
        assertNull(SavedLocations.SavedLocation.fromJson(missing))

        val partial = JSONObject().put("name", "X").put("lat", 0.0)
        assertNull(SavedLocations.SavedLocation.fromJson(partial))
    }

    @Test
    fun from_json_returns_null_for_invalid_coordinates() {
        val bad = JSONObject().put("name", "X").put("lat", 200.0).put("lng", 0.0)
        assertNull(SavedLocations.SavedLocation.fromJson(bad))

        val badName = JSONObject().put("name", "  ").put("lat", 0.0).put("lng", 0.0)
        assertNull(SavedLocations.SavedLocation.fromJson(badName))
    }
}