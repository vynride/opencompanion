// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.api.json
import io.github.vynride.opencompanion.core.config.Location
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherToolTest {
    private val sample =
        json
            .parseToJsonElement(
                """
                {
                  "current": {"temperature_2m": 21.5, "weather_code": 3, "relative_humidity_2m": 60},
                  "hourly": {
                    "time": ["2026-08-28T12:00", "2026-08-28T13:00", "2026-08-28T14:00", "2026-08-28T15:00",
                              "2026-08-28T16:00", "2026-08-28T17:00", "2026-08-28T18:00", "2026-08-28T19:00"],
                    "temperature_2m": [21, 22, 23, 23, 22, 21, 20, 19],
                    "precipitation_probability": [0, 0, 10, 40, 60, 60, 20, 0],
                    "weather_code": [3, 3, 3, 61, 61, 61, 3, 1]
                  }
                }
                """.trimIndent(),
            ).jsonObject

    private fun sampleWithPastHours() = json
        .parseToJsonElement(
            """
                {
                  "current": {"temperature_2m": 21.5, "weather_code": 3, "relative_humidity_2m": 60},
                  "hourly": {
                    "time": ["2026-08-28T08:00", "2026-08-28T09:00", "2026-08-28T10:00", "2026-08-28T11:00",
                              "2026-08-28T12:00", "2026-08-28T13:00", "2026-08-28T14:00", "2026-08-28T15:00",
                              "2026-08-28T16:00", "2026-08-28T17:00", "2026-08-28T18:00", "2026-08-28T19:00",
                              "2026-08-28T20:00", "2026-08-28T21:00"],
                    "temperature_2m": [21, 22, 18, 19, 20, 21, 22, 18, 19, 20, 21, 22, 18, 19],
                    "precipitation_probability": [0, 0, 0, 0, 10, 40, 60, 60, 20, 0, 0, 0, 0, 0],
                    "weather_code": [3, 3, 3, 3, 3, 61, 61, 61, 3, 1, 1, 1, 1, 1]
                  }
                }
            """.trimIndent(),
        ).jsonObject

    @Test
    fun `summarize reports now and the next hours`() {
        val text = summarizeWeather(sample, hours = 6, now = LocalDateTime.of(2026, 8, 28, 12, 0))
        assertTrue(text.startsWith("Now: 21.5°C, overcast, humidity 60%."))
        assertTrue(text.lowercase().contains("rain"))
        assertTrue(text.contains("60%"))
        assertFalse(text.contains("19:00"))
    }

    @Test
    fun `summarize starts at now truncated to the hour not start of day`() {
        val text = summarizeWeather(sampleWithPastHours(), hours = 6, now = LocalDateTime.of(2026, 8, 28, 12, 30))
        assertTrue(text.contains("12:00"))
        assertTrue(text.contains("17:00"))
        assertFalse(text.contains("08:00"))
        assertFalse(text.contains("11:00"))
        assertFalse(text.contains("18:00"))
    }

    @Test
    fun `weather tool hits open-meteo with configured coords`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = sample.toString()))
            server.start()
            val location = Location(lat = 1.5, lon = 2.5, timezone = "UTC")
            val tools = weatherTool(OkHttpClient(), location, server.url("/v1/forecast").toString())
            val text = tools.first().call(kotlinx.serialization.json.buildJsonObject {})
            assertTrue(text.contains("Now:"))
            val req = server.takeRequest()
            assertEquals("1.5", req.url.queryParameter("latitude"))
            assertEquals("2.5", req.url.queryParameter("longitude"))
        }
    }

    @Test
    fun `weather tool reports failure`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 500))
            server.start()
            val tools = weatherTool(OkHttpClient(), Location(), server.url("/v1/forecast").toString())
            val text = tools.first().call(kotlinx.serialization.json.buildJsonObject {})
            assertTrue(text.startsWith("Weather unavailable"))
        }
    }
}
