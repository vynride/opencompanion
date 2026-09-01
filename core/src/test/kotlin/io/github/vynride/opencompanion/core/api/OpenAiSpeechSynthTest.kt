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
    private fun synth(server: MockWebServer) = OpenAiSpeechSynth(
        OkHttpClient(),
        Service(server.url("/v1").toString(), "key", "tts-model"),
        TtsConfig(voice = "alto", speed = 1.1, instructions = "warm"),
        Log.Stdout,
    )

    @Test
    fun `synthesize requests wav with the configured voice`() = runTest {
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
    fun `stream yields the pcm body in chunks`() = runTest {
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

    @Test
    fun `stream drops at most one trailing byte of an odd body and keeps chunks even`() = runTest {
        MockWebServer().use { server ->
            val pcm = ByteArray(10001) { it.toByte() }
            server.enqueue(MockResponse.Builder().body(Buffer().write(pcm)).build())
            server.start()
            val chunks = synth(server).stream("hello").toList()
            assertEquals(emptyList(), chunks.filter { it.size % 2 != 0 })
            assertContentEquals(pcm.copyOf(10000), chunks.fold(ByteArray(0)) { a, b -> a + b })
        }
    }

    @Test
    fun `the aligner carries odd boundaries without losing bytes`() {
        val input = ByteArray(13) { it.toByte() }
        val aligner = SampleAligner()
        var read = 0
        val out = mutableListOf<ByteArray>()
        for (size in listOf(3, 5, 4, 1)) {
            out += aligner.align(input.copyOfRange(read, read + size))
            read += size
        }
        assertEquals(emptyList(), out.filter { it.size % 2 != 0 })
        // Everything except the final truncated byte survives, in order.
        assertContentEquals(input.copyOf(12), out.fold(ByteArray(0)) { a, b -> a + b })
    }
}
