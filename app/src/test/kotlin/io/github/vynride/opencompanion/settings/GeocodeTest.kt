// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeocodeTest {
    @Test
    fun `city search results carry name, coordinates, region and timezone`() {
        val body =
            """
            {"results":[
              {"id":1,"name":"Springfield","latitude":39.8,"longitude":-89.6,
               "country":"United States","admin1":"Illinois","timezone":"America/Chicago"},
              {"id":2,"name":"Springfield","latitude":42.1,"longitude":-72.5,
               "country":"United States","admin1":"Massachusetts","timezone":"America/New_York"}
            ],"generationtime_ms":0.5}
            """.trimIndent()
        val places = parseCitySearch(body)
        assertEquals(2, places.size)
        val first = places[0]
        assertEquals("Springfield", first.name)
        assertEquals(39.8, first.latitude)
        assertEquals(-89.6, first.longitude)
        assertEquals("America/Chicago", first.timezone)
        assertEquals("Illinois, United States", first.detail)
    }

    @Test
    fun `a search with no results parses to an empty list`() {
        assertEquals(emptyList(), parseCitySearch("""{"generationtime_ms":0.2}"""))
    }

    @Test
    fun `a result without admin1 shows only the country`() {
        val body = """{"results":[{"name":"Singapore","latitude":1.35,"longitude":103.8,"country":"Singapore"}]}"""
        assertEquals("Singapore", parseCitySearch(body).single().detail)
    }

    @Test
    fun `ip lookup maps city and coordinates`() {
        val body =
            """
            {"ip":"203.0.113.9","city":"Wellington","region":"Wellington","country_name":"New Zealand",
             "latitude":-41.29,"longitude":174.78,"timezone":"Pacific/Auckland","org":"Example"}
            """.trimIndent()
        val place = parseIpLocation(body)!!
        assertEquals("Wellington", place.name)
        assertEquals(-41.29, place.latitude)
        assertEquals(174.78, place.longitude)
        assertEquals("Pacific/Auckland", place.timezone)
        assertEquals("New Zealand", place.country)
    }

    @Test
    fun `ip lookup without coordinates is rejected`() {
        assertNull(parseIpLocation("""{"error":true,"reason":"RateLimited"}"""))
    }
}
