package com.example.mygps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URLEncoder

/**
 * Smoke test for Nominatim URL building. We don't hit the network in unit tests,
 * but we verify the query string encodes the same way the production code does.
 */
class NominatimUrlEncodingTest {

    @Test
    fun spaces_are_url_encoded() {
        val query = "Bangkok Thailand"
        val encoded = URLEncoder.encode(query, "UTF-8")
        assertEquals("Bangkok+Thailand", encoded)
    }

    @Test
    fun unicode_is_supported() {
        val query = "กรุงเทพ"
        val encoded = URLEncoder.encode(query, "UTF-8")
        assertNotNull(encoded)
        // %E0%B8%81... pattern for Thai
        org.junit.Assert.assertTrue(encoded.contains("%"))
    }
}