// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import io.github.vynride.opencompanion.core.config.TtsConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OpenAiSpeechSynthTest {
    private fun synth(server: MockWebServer) =
        OpenAiSpeechSynth(
            OkHttpClient(),
            Service(server.url("/v1").toString(), "key", "tts-model"),
            TtsConfig(voice = "alto", speed = 1.1, instructions = "warm"),
            Log.Stdout,
        )

    @Test
    fun `synthesize requests wav with the configured voice`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(1, 2, 3))).build())
                server.start()
                assertContentEquals(byteArrayOf(1, 2, 3), synth(server).synthesize("hello"))
                val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                assertEquals("tts-model", body["model"]!!.jsonPrimitive.content)
                assertEquals("alto", body["voice"]!!.jsonPrimitive.content)
                assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
                assertEquals("warm", body["instructions"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `stream yields the pcm body in chunks`() =
        runTest {
            MockWebServer().use { server ->
                val pcm = ByteArray(10000) { it.toByte() }
                server.enqueue(MockResponse.Builder().body(Buffer().write(pcm)).build())
                server.start()
                val chunks = synth(server).stream("hello").toList()
                assertContentEquals(pcm, chunks.fold(ByteArray(0)) { a, b -> a + b })
                val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
            }
        }
}
