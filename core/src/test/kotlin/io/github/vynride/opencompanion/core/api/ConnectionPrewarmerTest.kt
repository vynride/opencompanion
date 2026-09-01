// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.api

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.config.Service
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPrewarmerTest {
    @Test
    fun `each service gets a head request and an unreachable one is ignored`() = runTest(UnconfinedTestDispatcher()) {
        MockWebServer().use { server ->
            // Even a rejected probe has opened the connection; the status is irrelevant.
            server.enqueue(MockResponse(code = 401))
            server.start()
            val services =
                listOf(
                    Service(server.url("/v1").toString(), "k", "m"),
                    Service("http://127.0.0.1:1/v1", "k", "m"),
                )
            ConnectionPrewarmer(OkHttpClient(), services, Log.Stdout, this).prewarm()
            val request = server.takeRequest()
            assertEquals("HEAD", request.method)
            assertEquals("/v1/models", request.url.encodedPath)
        }
    }
}
