// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanionConfigTest {
    private val api =
        ApiConfig(
            baseUrl = "https://host/v1",
            apiKey = "k",
            chatModel = "chat-x",
            transcribeModel = "stt-x",
            ttsModel = "",
        )

    @Test
    fun `service builds from shared values`() {
        val cfg = CompanionConfig(api = api)
        val chat = cfg.service(ServiceKind.CHAT)!!
        assertEquals("https://host/v1/chat/completions", chat.url("chat/completions"))
        assertEquals("chat-x", chat.model)
        assertEquals(mapOf("Authorization" to "Bearer k"), chat.headers())
    }

    @Test
    fun `service is null without a model`() {
        assertNull(CompanionConfig(api = api).service(ServiceKind.TTS))
    }

    @Test
    fun `service is null without a key`() {
        assertNull(CompanionConfig(api = api.copy(apiKey = "")).service(ServiceKind.CHAT))
    }

    @Test
    fun `per service override wins over shared values`() {
        val cfg =
            CompanionConfig(
                api =
                    api.copy(
                        transcribe = ServiceOverride(baseUrl = "https://other/v1", apiKey = "k2", authHeader = AuthHeader.API_KEY),
                    ),
            )
        val stt = cfg.service(ServiceKind.TRANSCRIBE)!!
        assertEquals("https://other/v1/audio/transcriptions", stt.url("audio/transcriptions"))
        assertEquals(mapOf("api-key" to "k2"), stt.headers())
    }

    @Test
    fun `query string on the base url is carried over`() {
        val s = Service("https://host/v1?api-version=1", "k", "m")
        assertEquals("https://host/v1/responses?api-version=1", s.url("responses"))
    }

    @Test
    fun `defaults match the documented values`() {
        val cfg = CompanionConfig()
        assertEquals("Soc", cfg.name)
        assertEquals(0.4f, cfg.wakeWord.threshold)
        assertEquals(300.0, cfg.state.idleToSleepS)
        assertEquals(4, cfg.brain.maxToolCalls)
        assertEquals(150, cfg.memory.maxFactsLines)
    }
}
