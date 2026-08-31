// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.bus.Wake
import io.github.vynride.opencompanion.core.config.ApiConfig
import io.github.vynride.opencompanion.core.config.CompanionConfig
import io.github.vynride.opencompanion.core.state.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionTest {
    @TempDir
    lateinit var dir: Path

    private fun ports(
        input: FakeAudioInput = FakeAudioInput(),
        camera: FakeCamera? = FakeCamera(),
    ) = Ports(
        audioInput = input,
        audioOutput = FakeAudioOutput(),
        camera = camera,
        sensors = FakeSensors(emptyFlow()),
        screen = FakeScreen(),
        haptics = FakeHaptics(),
        notifications = FakeNotifications(),
        clipboard = FakeClipboard(),
        models = RepoModelStore(),
        clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z")),
    )

    @Test
    fun `starts without api keys and follows a turn through the states`() =
        runTest(UnconfinedTestDispatcher()) {
            val input = FakeAudioInput()
            val c = Companion(CompanionConfig(), ports(input), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c.start()
            assertTrue(input.started)
            c.bus.publish(Wake)
            advanceUntilIdle()
            assertEquals(State.LISTENING, c.state.value)
            c.bus.publish(Transcript("hi"))
            c.bus.publish(Reply("hello"))
            advanceUntilIdle()
            c.bus.publish(PlaybackDone)
            advanceUntilIdle()
            assertEquals(State.LISTENING, c.state.value)
            c.stop()
        }

    @Test
    fun `start twice throws`() =
        runTest(UnconfinedTestDispatcher()) {
            val c = Companion(CompanionConfig(), ports(), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c.start()
            assertFailsWith<IllegalStateException> { c.start() }
            c.stop()
        }

    @Test
    fun `stop leaves the parent scope alive`() =
        runTest(UnconfinedTestDispatcher()) {
            val c1 = Companion(CompanionConfig(), ports(), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c1.start()
            c1.stop()

            var ran = false
            backgroundScope.launch { ran = true }
            advanceUntilIdle()
            assertTrue(ran)
            assertTrue(backgroundScope.isActive)

            val c2 = Companion(CompanionConfig(), ports(), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c2.start()
            c2.bus.publish(Wake)
            advanceUntilIdle()
            assertEquals(State.LISTENING, c2.state.value)
            c2.stop()
        }

    @Test
    fun `tool registry carries every wired tool`() =
        runTest(UnconfinedTestDispatcher()) {
            val c = Companion(CompanionConfig(), ports(), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c.start()
            assertEquals(
                listOf(
                    "get_time",
                    "set_timer",
                    "remember",
                    "recall",
                    "weather",
                    "web_search",
                    "check_host",
                    "laptop_notify",
                    "laptop_clipboard",
                    "laptop_notifications",
                    "look",
                ),
                c.toolNames,
            )
            c.stop()
        }

    @Test
    fun `look is absent without a camera`() =
        runTest(UnconfinedTestDispatcher()) {
            val c = Companion(CompanionConfig(), ports(camera = null), OkHttpClient(), dir, Log.Stdout, backgroundScope)
            c.start()
            assertTrue("look" !in c.toolNames)
            c.stop()
        }

    @Test
    fun `a full turn goes through the chat model onto the bus and into the journal`() =
        runTest(UnconfinedTestDispatcher()) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse(body = """{"choices":[{"message":{"role":"assistant","content":"hello there"}}]}"""),
                )
                server.start()
                val config =
                    CompanionConfig(
                        api = ApiConfig(baseUrl = server.url("/v1").toString(), apiKey = "k", chatModel = "m"),
                    )
                val c = Companion(config, ports(), OkHttpClient(), dir, Log.Stdout, backgroundScope)
                c.start()
                c.bus.publish(Transcript("hi"))
                val reply = c.bus.on<Reply>().first()
                assertEquals("hello there", reply.text)
                assertTrue("hello there" in c.memory.journalToday())
                c.stop()
            }
        }
}
