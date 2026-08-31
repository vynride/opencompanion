// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import io.github.vynride.opencompanion.core.config.AuthHeader
import io.github.vynride.opencompanion.core.config.ChatApi
import io.github.vynride.opencompanion.core.config.CompanionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigMapperTest {
    @Test
    fun `blank settings map to CompanionConfig defaults`() {
        val config = ConfigMapper.toConfig(Settings())
        val defaults = CompanionConfig()

        assertEquals(defaults.name, config.name)
        assertEquals(defaults.location, config.location)
        assertEquals(defaults.api, config.api)
        assertEquals(defaults.wakeWord.modelFile, config.wakeWord.modelFile)
        assertEquals(defaults.brain.api, config.brain.api)
        assertEquals(defaults.tts.voice, config.tts.voice)
        assertEquals(defaults.tts.speed, config.tts.speed)
        assertEquals(defaults.search.exaApiKey, config.search.exaApiKey)
        assertEquals(defaults.hosts, config.hosts)
        assertEquals(defaults.cameraEnabled, config.cameraEnabled)
    }

    @Test
    fun `a fully populated settings maps every field`() {
        val settings =
            Settings(
                companionName = "Ada",
                locationName = "Bengaluru",
                lat = 12.97,
                lon = 77.59,
                timezone = "Asia/Kolkata",
                baseUrl = "https://example.test/v1",
                apiKey = "sk-test",
                authHeader = "api-key",
                chatModel = "gpt-chat",
                transcribeModel = "gpt-transcribe",
                ttsModel = "gpt-tts",
                chatApi = "responses",
                voice = "alloy",
                speed = 1.5f,
                wakeThreshold = 0.6f,
                wakeModelFile = "wakeword/custom.onnx",
                exaKey = "exa-test",
                laptopHost = "192.168.1.5",
                cameraEnabled = false,
                kioskPinned = true,
                cameraStepDone = true,
            )

        val config = ConfigMapper.toConfig(settings)

        assertEquals("Ada", config.name)
        assertEquals(12.97, config.location.lat)
        assertEquals(77.59, config.location.lon)
        assertEquals("Asia/Kolkata", config.location.timezone)
        assertEquals("gpt-chat", config.api.chatModel)
        assertEquals("gpt-transcribe", config.api.transcribeModel)
        assertEquals("gpt-tts", config.api.ttsModel)
        assertEquals(AuthHeader.API_KEY, config.api.authHeader)
        assertEquals(ChatApi.RESPONSES, config.brain.api)
        assertEquals("alloy", config.tts.voice)
        assertEquals(1.5, config.tts.speed)
        assertEquals(0.6f, config.wakeWord.threshold)
        assertEquals("wakeword/custom.onnx", config.wakeWord.modelFile)
        assertEquals("exa-test", config.search.exaApiKey)
        assertEquals(mapOf("laptop" to "192.168.1.5"), config.hosts.hosts)
        assertEquals(false, config.cameraEnabled)
    }

    @Test
    fun `coordinates without a location name still map`() {
        val config = ConfigMapper.toConfig(Settings(lat = 12.97, lon = 77.59))
        val defaults = CompanionConfig()

        assertEquals(defaults.location.name, config.location.name)
        assertEquals(12.97, config.location.lat)
        assertEquals(77.59, config.location.lon)
    }

    @Test
    fun `a location name without coordinates keeps the default coordinates`() {
        val config = ConfigMapper.toConfig(Settings(locationName = "Somewhere"))
        val defaults = CompanionConfig()

        assertEquals("Somewhere", config.location.name)
        assertEquals(defaults.location.lat, config.location.lat)
        assertEquals(defaults.location.lon, config.location.lon)
    }

    @Test
    fun `speed and threshold clamp to their sane ranges`() {
        val tooFast = ConfigMapper.toConfig(Settings(speed = 9f))
        assertEquals(2.0, tooFast.tts.speed)

        val tooSlow = ConfigMapper.toConfig(Settings(speed = 0f))
        assertEquals(0.5, tooSlow.tts.speed)

        val tooHigh = ConfigMapper.toConfig(Settings(wakeThreshold = 1f))
        assertEquals(0.95f, tooHigh.wakeWord.threshold)

        val tooLow = ConfigMapper.toConfig(Settings(wakeThreshold = 0f))
        assertEquals(0.05f, tooLow.wakeWord.threshold)
    }
}
