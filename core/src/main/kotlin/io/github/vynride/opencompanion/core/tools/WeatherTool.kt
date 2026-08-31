// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.api.await
import io.github.vynride.opencompanion.core.api.json
import io.github.vynride.opencompanion.core.api.requireSuccess
import io.github.vynride.opencompanion.core.config.Location
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime

const val OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"

val WMO =
    mapOf(
        0 to "clear",
        1 to "mostly clear",
        2 to "partly cloudy",
        3 to "overcast",
        45 to "fog",
        48 to "rime fog",
        51 to "light drizzle",
        53 to "drizzle",
        55 to "heavy drizzle",
        61 to "light rain",
        63 to "rain",
        65 to "heavy rain",
        71 to "light snow",
        73 to "snow",
        75 to "heavy snow",
        80 to "rain showers",
        81 to "heavy showers",
        82 to "violent showers",
        95 to "thunderstorm",
        96 to "thunderstorm with hail",
        99 to "severe thunderstorm",
    )

fun weatherWords(code: Int): String = WMO[code] ?: "code $code"

suspend fun fetchWeather(
    http: OkHttpClient,
    baseUrl: String,
    lat: Double,
    lon: Double,
    tz: String,
): JsonObject {
    val url =
        baseUrl
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("timezone", tz)
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("current", "temperature_2m,weather_code,relative_humidity_2m")
            .addQueryParameter("hourly", "temperature_2m,precipitation_probability,weather_code")
            .build()
    val request = Request.Builder().url(url).build()
    return http.await(request).requireSuccess().use { json.parseToJsonElement(it.body.string()).jsonObject }
}

fun summarizeWeather(
    data: JsonObject,
    hours: Int = 6,
    now: LocalDateTime = LocalDateTime.now(),
): String {
    val cur = data["current"]!!.jsonObject
    val temp = cur["temperature_2m"]!!.jsonPrimitive.content
    val code = cur["weather_code"]!!.jsonPrimitive.int
    val humidity = cur["relative_humidity_2m"]!!.jsonPrimitive.content
    val nowLine = "Now: $temp°C, ${weatherWords(code)}, humidity $humidity%."

    val h = data["hourly"]!!.jsonObject
    val times = h["time"]!!.jsonArray.map { it.jsonPrimitive.content }
    val temps = h["temperature_2m"]!!.jsonArray.map { it.jsonPrimitive.content }
    val pops = h["precipitation_probability"]!!.jsonArray.map { it.jsonPrimitive.content }
    val codes = h["weather_code"]!!.jsonArray.map { it.jsonPrimitive.int }

    val cutoff = now.withMinute(0).withSecond(0).withNano(0)
    val parsedTimes = times.map { LocalDateTime.parse(it) }
    val start = parsedTimes.indexOfFirst { it >= cutoff }.let { if (it == -1) times.size else it }

    val parts =
        (start until minOf(start + hours, times.size)).map { i ->
            "${times[i].substring(11, 16)} ${temps[i]}°C ${weatherWords(codes[i])} (${pops[i]}% rain)"
        }
    return "$nowLine Next hours: ${parts.joinToString("; ")}."
}

fun weatherTool(
    http: OkHttpClient,
    location: Location,
    baseUrl: String = OPEN_METEO_URL,
): List<Tool> =
    listOf(
        Tool("weather", "Current weather and the next six hours at the configured location.", objectSchema()) {
            try {
                summarizeWeather(fetchWeather(http, baseUrl, location.lat, location.lon, location.timezone))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Weather unavailable: ${e.message}"
            }
        },
    )
