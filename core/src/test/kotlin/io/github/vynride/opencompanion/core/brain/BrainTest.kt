// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.brain

import io.github.vynride.opencompanion.core.FixedClock
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.Chat
import io.github.vynride.opencompanion.core.api.toolMessage
import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.config.BrainConfig
import io.github.vynride.opencompanion.core.config.CompanionConfig
import io.github.vynride.opencompanion.core.memory.Memory
import io.github.vynride.opencompanion.core.tools.Tool
import io.github.vynride.opencompanion.core.tools.ToolRegistry
import io.github.vynride.opencompanion.core.tools.objectSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun toolCall(
    name: String,
    arguments: String = "{}",
    id: String = "call_1",
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("arguments", arguments)
        }
    }

private fun assistant(
    content: String? = null,
    calls: List<JsonObject> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("role", "assistant")
        put("content", if (content == null) JsonNull else kotlinx.serialization.json.JsonPrimitive(content))
        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
    }

/** Hands out canned assistant messages and records the (messages, tools) pair of every call. */
private class ScriptedChat(
    vararg responses: JsonObject,
) : Chat {
    override val model = "chat-model"
    val seen = mutableListOf<Pair<List<JsonObject>, List<JsonObject>?>>()
    private val queue = ArrayDeque(responses.toList())

    override suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
    ): JsonObject {
        seen += messages.toList() to tools
        return queue.removeFirstOrNull() ?: error("scripted chat ran out of responses")
    }
}

private class FailingChat(
    private val error: () -> Throwable = { IOException("boom") },
) : Chat {
    override val model = "chat-model"

    override suspend fun chat(
        messages: List<JsonObject>,
        tools: List<JsonObject>?,
    ): JsonObject = throw error()
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrainTest {
    @TempDir
    lateinit var dir: Path

    private class Harness(
        scope: TestScope,
        root: Path,
        val llm: Chat?,
        vision: Boolean = true,
    ) {
        val bus = EventBus()
        val clock = FixedClock(Instant.parse("2026-08-28T12:00:00Z"))
        val memory = Memory(root, 150, clock)
        val tools = ToolRegistry(bus)
        val events = mutableListOf<Event>()
        val slowRuns = mutableListOf<String>()
        val seenArgs = mutableListOf<JsonObject>()
        val brain: Brain

        init {
            Files.write(root.resolve("personality.md"), "You are Soc.\n".toByteArray())
            memory.remember("The user likes tea")
            tools.register(Tool("get_time", "d", objectSchema()) { "noon" })
            tools.register(
                Tool("slow", "d", objectSchema()) {
                    delay(50)
                    slowRuns += "done"
                    "done"
                },
            )
            tools.register(
                Tool("counted", "d", objectSchema()) {
                    seenArgs += it
                    "ran"
                },
            )
            val config =
                CompanionConfig(
                    brain =
                        BrainConfig(
                            vision = vision,
                            maxTurns = 2,
                            sessionResetMin = 30,
                            fillerAfterS = 0.01,
                            fillers = listOf("Hmm."),
                        ),
                )
            brain =
                Brain(
                    bus,
                    config,
                    memory,
                    llm,
                    tools,
                    { mapOf("battery" to "80%") },
                    clock,
                    Log.Stdout,
                    scope.backgroundScope,
                )
            bus.events.onEach { events += it }.launchIn(scope.backgroundScope)
        }

        val replies get() = events.filterIsInstance<Reply>()
    }

    private fun scripted(vararg responses: JsonObject) = ScriptedChat(*responses)

    private fun usersIn(messages: List<JsonObject>): List<String> =
        messages.filter { it["role"]?.jsonPrimitive?.content == "user" }.map { it["content"]!!.jsonPrimitive.content }

    @Test
    fun `system prompt carries name, personality, facts, journal and runtime`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, scripted())
            h.memory.journalAppend("user", "earlier line")
            val p = h.brain.buildSystemPrompt()
            assertTrue("Your name is Soc." in p)
            assertTrue("You are Soc." in p)
            assertTrue("- The user likes tea" in p)
            assertTrue("earlier line" in p)
            assertTrue("battery: 80%" in p)
            assertTrue("location: Somewhere" in p)
            assertTrue("spoken sentence" in p)
            assertTrue("no dashes" in p)
        }

    @Test
    fun `a plain reply is published and journalled on both sides`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, scripted(assistant("Hello there.")))
            assertEquals("Hello there.", h.brain.handle("hi"))
            assertEquals(listOf(Reply("Hello there.")), h.replies)
            val journal = h.memory.journalToday()
            assertTrue("user: hi" in journal)
            assertTrue("soc: Hello there." in journal)
        }

    @Test
    fun `blank input is ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted()
            val h = Harness(this, dir, llm)
            assertEquals("", h.brain.handle("   "))
            assertTrue(h.replies.isEmpty())
            assertTrue(llm.seen.isEmpty())
        }

    @Test
    fun `one tool call then an answer feeds the result back with tools still offered`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant(calls = listOf(toolCall("get_time"))), assistant("It is noon."))
            val h = Harness(this, dir, llm)
            assertEquals("It is noon.", h.brain.handle("what time is it"))
            assertEquals(listOf(Reply("It is noon.")), h.replies)
            val (messages, tools) = llm.seen[1]
            assertEquals(toolMessage("call_1", "noon"), messages.last())
            assertNotNull(tools)
        }

    @Test
    fun `an unknown tool is reported back to the model`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant(calls = listOf(toolCall("nope"))), assistant("I cannot do that."))
            val h = Harness(this, dir, llm)
            assertEquals("I cannot do that.", h.brain.handle("do a thing"))
            assertEquals(toolMessage("call_1", "Unknown tool: nope"), llm.seen[1].first.last())
        }

    @Test
    fun `a slow tool call gets a filler`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, scripted(assistant(calls = listOf(toolCall("slow"))), assistant("It is done.")))
            assertEquals("It is done.", h.brain.handle("do the slow thing"))
            assertEquals(listOf(Say("Hmm.")), h.events.filterIsInstance<Say>())
            assertEquals(listOf("done"), h.slowRuns)
        }

    @Test
    fun `a fast tool call says no filler`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, scripted(assistant(calls = listOf(toolCall("get_time"))), assistant("It is noon.")))
            assertEquals("It is noon.", h.brain.handle("what time is it"))
            assertTrue(h.events.filterIsInstance<Say>().isEmpty())
        }

    @Test
    fun `parallel calls past the budget are skipped while executed ones count`() =
        runTest(UnconfinedTestDispatcher()) {
            val batch = (0 until 6).map { toolCall("counted", id = "c$it") }
            val llm = scripted(assistant(calls = batch), assistant("that is plenty"))
            val h = Harness(this, dir, llm)
            assertEquals("that is plenty", h.brain.handle("do six things"))
            assertEquals(4, h.seenArgs.size)
            val results =
                llm.seen[1]
                    .first
                    .filter { it["role"]?.jsonPrimitive?.content == "tool" }
                    .map { it["content"]!!.jsonPrimitive.content }
            assertEquals(List(4) { "ran" }, results.take(4))
            assertEquals(List(2) { SKIPPED_CALL }, results.drop(4))
            assertNull(llm.seen[1].second)
        }

    @Test
    fun `asking for tools with the budget spent gives up with out of steps`() =
        runTest(UnconfinedTestDispatcher()) {
            val batch = (0 until 4).map { toolCall("get_time", id = "c$it") }
            val llm = scripted(assistant(calls = batch), assistant(calls = listOf(toolCall("get_time", id = "late"))))
            val h = Harness(this, dir, llm)
            assertEquals(OUT_OF_STEPS, h.brain.handle("go"))
            assertEquals(2, llm.seen.size)
        }

    @Test
    fun `unparseable tool arguments become an empty object`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant(calls = listOf(toolCall("counted", "{oops"))), assistant("done"))
            val h = Harness(this, dir, llm)
            assertEquals("done", h.brain.handle("go"))
            assertEquals(listOf(JsonObject(emptyMap())), h.seenArgs)
        }

    @Test
    fun `history keeps maxTurns pairs and resets after an idle gap`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant("ok"), assistant("ok"), assistant("ok"), assistant("ok"))
            val h = Harness(this, dir, llm)
            repeat(3) { h.brain.handle("turn $it") }
            assertEquals(listOf("turn 1", "turn 2"), usersIn(llm.seen.last().first))
            h.clock.instant = Instant.parse("2026-08-28T13:00:00Z")
            h.brain.handle("after break")
            assertEquals(listOf("after break"), usersIn(llm.seen.last().first))
        }

    @Test
    fun `an io failure publishes Failure and no Reply`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, FailingChat())
            assertEquals("", h.brain.handle("hi"))
            assertTrue(h.replies.isEmpty())
            assertEquals(
                "the chat model",
                h.events
                    .filterIsInstance<Failure>()
                    .single()
                    .source,
            )
        }

    @Test
    fun `a non-io failure also publishes Failure and no Reply`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, FailingChat { IllegalStateException("bad body") })
            assertEquals("", h.brain.handle("hi"))
            assertTrue(h.replies.isEmpty())
            assertEquals(
                "the chat model",
                h.events
                    .filterIsInstance<Failure>()
                    .single()
                    .source,
            )
        }

    @Test
    fun `without a chat model the brain says so exactly once`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, null)
            assertEquals(NO_LLM_REPLY, h.brain.handle("hi"))
            assertEquals("", h.brain.handle("hi again"))
            assertEquals(listOf(Reply(NO_LLM_REPLY)), h.replies)
        }

    @Test
    fun `vision sends the image with no tools`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant("a cup"))
            val h = Harness(this, dir, llm)
            assertEquals("a cup", h.brain.askVision("what?", byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
            val (messages, tools) = llm.seen.single()
            assertNull(tools)
            assertEquals(2, messages.size)
            val parts = messages.last()["content"] as JsonArray
            assertEquals("what?", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue(
                parts[1]
                    .jsonObject["image_url"]!!
                    .jsonObject["url"]!!
                    .jsonPrimitive.content
                    .startsWith("data:image/jpeg;base64,"),
            )
        }

    @Test
    fun `vision disabled skips the model`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted()
            val h = Harness(this, dir, llm, vision = false)
            assertTrue("vision" in h.brain.askVision("what?", byteArrayOf(1)))
            assertTrue(llm.seen.isEmpty())
        }

    @Test
    fun `vision without a chat model says it is not configured`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, null)
            assertEquals("Vision is not configured.", h.brain.askVision("what?", byteArrayOf(1)))
        }

    @Test
    fun `yesterdays journal is summarized into facts exactly once`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted(assistant("- set a tea timer\n- nothing else"))
            val h = Harness(this, dir, llm)
            val yesterday = LocalDate.parse("2026-08-27")
            Files.createDirectories(h.memory.journalPath(yesterday).parent)
            Files.write(h.memory.journalPath(yesterday), "- 10:00 user: set a tea timer\n".toByteArray())
            h.brain.maybeSummarizeYesterday()
            h.brain.maybeSummarizeYesterday()
            assertEquals(1, llm.seen.size)
            assertTrue(h.memory.hasSummary(yesterday))
            assertTrue("- set a tea timer" in h.memory.facts())
        }

    @Test
    fun `no summary is attempted without a journal`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm = scripted()
            val h = Harness(this, dir, llm)
            h.brain.maybeSummarizeYesterday()
            assertTrue(llm.seen.isEmpty())
            assertTrue(!h.memory.hasSummary(LocalDate.parse("2026-08-27")))
        }

    @Test
    fun `a transcript event drives a turn`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this, dir, scripted(assistant("yo")))
            h.brain.start()
            h.bus.publish(Transcript("hey"))
            assertEquals(listOf(Reply("yo")), h.replies)
            h.brain.stop()
        }

    @Test
    fun `a throwing handler does not kill the collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val llm =
                object : Chat {
                    override val model = "chat-model"
                    var calls = 0

                    override suspend fun chat(
                        messages: List<JsonObject>,
                        tools: List<JsonObject>?,
                    ): JsonObject {
                        calls++
                        check(calls != 1) { "bad turn" }
                        return assistant("second")
                    }
                }
            val h = Harness(this, dir, llm)
            h.brain.start()
            h.bus.publish(Transcript("one"))
            h.bus.publish(Transcript("two"))
            assertEquals(2, llm.calls)
            assertEquals(listOf(Reply("second")), h.replies)
            h.brain.stop()
        }
}
