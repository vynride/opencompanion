// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** One place a search or network lookup can fill the location fields from. */
@Serializable
data class GeoPlace(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val admin1: String = "",
    val country: String = "",
    val timezone: String = "",
) {
    /** "Name, admin1, country" with blanks dropped. */
    val detail: String get() = listOf(admin1, country).filter { it.isNotBlank() }.joinToString(", ")
}

@Serializable
private data class GeoResults(
    val results: List<GeoPlace> = emptyList(),
)

@Serializable
private data class IpLocation(
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String = "",
    @SerialName("country_name") val countryName: String = "",
)

private val json = Json { ignoreUnknownKeys = true }

fun parseCitySearch(body: String): List<GeoPlace> = json.decodeFromString<GeoResults>(body).results

/** Null when the response carries no usable coordinates. */
fun parseIpLocation(body: String): GeoPlace? {
    val loc = json.decodeFromString<IpLocation>(body)
    if (loc.latitude == null || loc.longitude == null) return null
    return GeoPlace(
        name = loc.city,
        latitude = loc.latitude,
        longitude = loc.longitude,
        country = loc.countryName,
        timezone = loc.timezone,
    )
}

private suspend fun fetch(
    http: OkHttpClient,
    url: String,
): String = withContext(Dispatchers.IO) {
    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body.string()
    }
}

/** Free open-meteo geocoding; no key needed. */
internal suspend fun searchCities(
    http: OkHttpClient,
    query: String,
): List<GeoPlace> {
    val q = URLEncoder.encode(query, "UTF-8")
    return parseCitySearch(fetch(http, "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=5&language=en&format=json"))
}

/** Coarse position from the network address; no key needed. */
internal suspend fun detectFromNetwork(http: OkHttpClient): GeoPlace? = parseIpLocation(fetch(http, "https://ipapi.co/json/"))
