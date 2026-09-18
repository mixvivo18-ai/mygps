package com.example.mygps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [MockLocationEngine] validators.
 * These are Android-framework-free and can be compiled with plain `kotlinc`.
 */
class MockLocationEngineValidatorTest {

    @Test
    fun lat_zero_is_valid() {
        assertTrue(MockLocationEngine.isValidLat(0.0))
    }

    @Test
    fun lat_north_pole_is_valid() {
        assertTrue(MockLocationEngine.isValidLat(90.0))
    }

    @Test
    fun lat_south_pole_is_valid() {
        assertTrue(MockLocationEngine.isValidLat(-90.0))
    }

    @Test
    fun lat_above_range_is_invalid() {
        assertFalse(MockLocationEngine.isValidLat(90.0001))
        assertFalse(MockLocationEngine.isValidLat(180.0))
    }

    @Test
    fun lat_below_range_is_invalid() {
        assertFalse(MockLocationEngine.isValidLat(-90.0001))
        assertFalse(MockLocationEngine.isValidLat(-180.0))
    }

    @Test
    fun lat_nan_is_invalid() {
        assertFalse(MockLocationEngine.isValidLat(Double.NaN))
    }

    @Test
    fun lng_zero_is_valid() {
        assertTrue(MockLocationEngine.isValidLng(0.0))
    }

    @Test
    fun lng_extremes_are_valid() {
        assertTrue(MockLocationEngine.isValidLng(180.0))
        assertTrue(MockLocationEngine.isValidLng(-180.0))
    }

    @Test
    fun lng_outside_range_is_invalid() {
        assertFalse(MockLocationEngine.isValidLng(180.0001))
        assertFalse(MockLocationEngine.isValidLng(-180.0001))
        assertFalse(MockLocationEngine.isValidLng(360.0))
    }

    @Test
    fun lng_nan_is_invalid() {
        assertFalse(MockLocationEngine.isValidLng(Double.NaN))
    }

    @Test
    fun bangkok_lat_lng_is_valid() {
        assertTrue(MockLocationEngine.isValidLat(13.7563))
        assertTrue(MockLocationEngine.isValidLng(100.5018))
    }
}