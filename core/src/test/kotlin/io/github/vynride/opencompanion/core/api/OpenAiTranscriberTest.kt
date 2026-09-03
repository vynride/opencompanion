// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiTranscriberTest {
    @Test
    fun `posts multipart wav and returns the text`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"text":" what time is it "}"""))
            server.start()
            val svc = Service(server.url("/v1").toString(), "key", "stt-model")
            val t = OpenAiTranscriber(OkHttpClient(), svc, "en", Log.Stdout)
            assertEquals("what time is it", t.transcribe(byteArrayOf(1, 2, 3)))
            val req = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
            assertEquals("Bearer key", req.headers["Authorization"])
            val body = req.body!!.utf8()
            assertTrue("name=\"model\"" in body && "stt-model" in body)
            assertTrue("name=\"language\"" in body && "en" in body)
            assertTrue("filename=\"audio.wav\"" in body)
        }
    }

    @Test
    fun `retries a 503 then succeeds`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 503))
            server.enqueue(MockResponse(body = """{"text":"ok"}"""))
            server.start()
            val svc = Service(server.url("/v1").toString(), "key", "m")
            val t = OpenAiTranscriber(OkHttpClient(), svc, "en", Log.Stdout)
            assertEquals("ok", t.transcribe(byteArrayOf(0)))
            assertEquals(2, server.requestCount)
        }
    }
}
