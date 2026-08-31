// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import io.github.vynride.opencompanion.core.config.ApiConfig
import io.github.vynride.opencompanion.core.config.AuthHeader
import io.github.vynride.opencompanion.core.config.ChatApi
import io.github.vynride.opencompanion.core.config.CompanionConfig
import io.github.vynride.opencompanion.core.config.HostsConfig
import io.github.vynride.opencompanion.core.config.Location
import io.github.vynride.opencompanion.core.config.SearchConfig
import io.github.vynride.opencompanion.core.config.TtsConfig
import io.github.vynride.opencompanion.core.config.WakeWordConfig

/** Everything the settings screen edits, stored as plain preferences values. Null coordinates mean unset. */
data class Settings(
    val companionName: String = "",
    val locationName: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val timezone: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val authHeader: String = "",
    val chatModel: String = "",
    val transcribeModel: String = "",
    val ttsModel: String = "",
    val chatApi: String = "",
    val voice: String = "",
    val speed: Float = 1.0f,
    val wakeThreshold: Float = 0.4f,
    val wakeModelFile: String = "",
    val exaKey: String = "",
    val laptopHost: String = "",
    val cameraEnabled: Boolean = true,
    val kioskPinned: Boolean = false,
    val cameraStepDone: Boolean = false,
)

/** Pure mapping from stored [Settings] to the runtime [CompanionConfig], no Android types. */
object ConfigMapper {
    fun toConfig(s: Settings): CompanionConfig {
        val defaults = CompanionConfig()
        return defaults.copy(
            name = s.companionName.ifBlank { defaults.name },
            location =
            Location(
                name = s.locationName.ifBlank { defaults.location.name },
                lat = s.lat ?: defaults.location.lat,
                lon = s.lon ?: defaults.location.lon,
                timezone = s.timezone.ifBlank { defaults.location.timezone },
            ),
            api =
            ApiConfig(
                baseUrl = s.baseUrl.ifBlank { defaults.api.baseUrl },
                apiKey = s.apiKey.ifBlank { defaults.api.apiKey },
                authHeader = if (s.authHeader == "api-key") AuthHeader.API_KEY else AuthHeader.AUTHORIZATION,
                chatModel = s.chatModel.ifBlank { defaults.api.chatModel },
                transcribeModel = s.transcribeModel.ifBlank { defaults.api.transcribeModel },
                ttsModel = s.ttsModel.ifBlank { defaults.api.ttsModel },
            ),
            wakeWord =
            defaults.wakeWord.copy(
                modelFile = s.wakeModelFile.ifBlank { defaults.wakeWord.modelFile },
                threshold = s.wakeThreshold.coerceIn(0.05f, 0.95f),
            ),
            brain =
            defaults.brain.copy(
                api = if (s.chatApi == "responses") ChatApi.RESPONSES else ChatApi.CHAT,
            ),
            tts =
            TtsConfig(
                voice = s.voice.ifBlank { defaults.tts.voice },
                speed = s.speed.coerceIn(0.5f, 2.0f).toDouble(),
                instructions = defaults.tts.instructions,
            ),
            search =
            SearchConfig(
                maxResults = defaults.search.maxResults,
                maxChars = defaults.search.maxChars,
                exaApiKey = s.exaKey.ifBlank { defaults.search.exaApiKey },
            ),
            hosts =
            if (s.laptopHost.isBlank()) {
                defaults.hosts
            } else {
                HostsConfig(hosts = mapOf("laptop" to s.laptopHost))
            },
            cameraEnabled = s.cameraEnabled,
        )
    }
}
