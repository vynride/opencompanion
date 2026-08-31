// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.brain

import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.api.Chat
import io.github.vynride.opencompanion.core.api.imageMessage
import io.github.vynride.opencompanion.core.api.json
import io.github.vynride.opencompanion.core.api.systemMessage
import io.github.vynride.opencompanion.core.api.toolMessage
import io.github.vynride.opencompanion.core.api.userMessage
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Say
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.config.CompanionConfig
import io.github.vynride.opencompanion.core.memory.Memory
import io.github.vynride.opencompanion.core.ports.Clock
import io.github.vynride.opencompanion.core.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

const val LENGTH_RULE =
    "Reply in ONE short spoken sentence (two only if truly needed); the reply is " +
        "read aloud and shown as a caption, so be brief and conversational. No markdown, " +
        "no lists, no dashes as punctuation: use commas or periods."
const val NO_LLM_REPLY = "My language model is not configured."
const val OUT_OF_STEPS = "I ran out of steps; ask me again."
const val SKIPPED_CALL = "Skipped: this turn's tool call budget is spent. Answer with what you already have."
const val SUMMARY_PROMPT =
    "Summarize this desk-companion journal into at most 5 short bullets " +
        "of facts worth remembering. Output only bullets starting with '- '."

/** Turns a Transcript into a Reply: prompt assembly, tool loop, session window. */
class Brain(
    private val bus: EventBus,
    private val config: CompanionConfig,
    private val memory: Memory,
    private val llm: Chat?,
    val tools: ToolRegistry,
    private val runtimeInfo: () -> Map<String, String>,
    private val clock: Clock,
    private val log: Log,
    private val scope: CoroutineScope,
) {
    private val history = ArrayList<JsonObject>()
    private var lastTurn: Instant? = null
    private var saidNoLlm = false
    private var wiring: Job? = null

    fun start() {
        wiring =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                bus.on<Transcript>().collect {
                    runCatching {
                        maybeSummarizeYesterday()
                        handle(it.text)
                    }.onFailure { e -> log.error("brain", "handler failed", e) }
                }
            }
    }

    fun stop() {
        wiring?.cancel()
    }

    fun buildSystemPrompt(): String {
        val time = clock.now().atZone(clock.zone()).format(DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH))
        val runtime = linkedMapOf("local time" to time, "location" to config.location.name)
        runtime.putAll(runtimeInfo())
        return listOf(
            "Your name is ${config.name}.",
            memory.personality().trim(),
            "## Facts\n" + memory.facts().trim().ifEmpty { "(none yet)" },
            "## Today's journal\n" + memory.journalToday().trim().ifEmpty { "(nothing yet)" },
            "## Runtime\n" + runtime.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            LENGTH_RULE,
        ).joinToString("\n\n")
    }

    private fun buildMessages(userText: String): MutableList<JsonObject> {
        val keep = if (config.brain.maxTurns > 1) history.takeLast((config.brain.maxTurns - 1) * 2) else emptyList()
        return (listOf(systemMessage(buildSystemPrompt())) + keep + userMessage(userText)).toMutableList()
    }

    private fun maybeResetSession() {
        val last = lastTurn
        if (last != null && Duration.between(last, clock.now()).toMinutes() > config.brain.sessionResetMin) {
            log.info("brain", "session reset after idle")
            history.clear()
        }
        lastTurn = clock.now()
    }

    suspend fun handle(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (llm == null) {
            if (saidNoLlm) return ""
            saidNoLlm = true
            log.warn("brain", "chat model not configured")
            bus.publish(Reply(NO_LLM_REPLY))
            return NO_LLM_REPLY
        }
        maybeResetSession()
        memory.journalAppend("user", trimmed)
        val messages = buildMessages(trimmed)
        val reply =
            try {
                loop(llm, messages)
            } catch (e: IOException) {
                log.error("brain", "chat failed", e)
                bus.publish(Failure("the chat model", e.message.orEmpty()))
                return ""
            }
        history += userMessage(trimmed)
        history +=
            buildJsonObject {
                put("role", "assistant")
                put("content", reply)
            }
        memory.journalAppend(config.name.lowercase(), reply)
        bus.publish(Reply(reply))
        return reply
    }

    /** Chat until the model answers without tool calls or the budget is spent. */
    private suspend fun loop(
        llm: Chat,
        messages: MutableList<JsonObject>,
    ): String {
        val specs = tools.specs()
        var spent = 0
        while (true) {
            val budget = config.brain.maxToolCalls - spent
            val offered = if (specs.isNotEmpty() && budget > 0) specs else null
            val msg = llm.chat(messages, offered)
            val calls = (msg["tool_calls"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            val content = ((msg["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()
            if (calls.isEmpty()) return content
            if (offered == null) {
                log.warn("brain", "model asked for tools with no budget left")
                return content.ifEmpty { OUT_OF_STEPS }
            }
            messages +=
                buildJsonObject {
                    put("role", "assistant")
                    put("content", msg["content"] ?: JsonNull)
                    put("tool_calls", JsonArray(calls))
                }
            for ((i, call) in calls.withIndex()) {
                val id = call["id"]!!.jsonPrimitive.content
                val fn = call["function"]!!.jsonObject
                if (i >= budget) {
                    log.warn("brain", "tool call budget spent, skipping ${fn["name"]?.jsonPrimitive?.content}")
                    messages += toolMessage(id, SKIPPED_CALL)
                    continue
                }
                val args =
                    try {
                        json
                            .parseToJsonElement(fn["arguments"]?.jsonPrimitive?.content?.ifBlank { "{}" } ?: "{}")
                            .jsonObject
                    } catch (e: Exception) {
                        log.warn("brain", "tool got unparseable arguments", e)
                        buildJsonObject {}
                    }
                messages += toolMessage(id, callWithFiller(fn["name"]!!.jsonPrimitive.content, args))
                spent++
            }
        }
    }

    /** Run a tool, speaking a short filler if it is still going after fillerAfterS. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun callWithFiller(
        name: String,
        args: JsonObject,
    ): String =
        coroutineScope {
            val task = async { tools.call(name, args) }
            select {
                task.onAwait { it }
                onTimeout(config.brain.fillerAfterS.seconds) {
                    bus.publish(Say(config.brain.fillers.random()))
                    task.await()
                }
            }
        }

    suspend fun askVision(
        question: String,
        jpeg: ByteArray,
    ): String {
        if (llm == null) return "Vision is not configured."
        if (!config.brain.vision) return "I can't see images right now (vision is disabled)."
        val msg = llm.chat(listOf(systemMessage(buildSystemPrompt()), imageMessage(question, jpeg)))
        return ((msg["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").trim()
    }

    /** On the first turn of a new day, fold yesterday's journal into facts.md. */
    suspend fun maybeSummarizeYesterday() {
        if (llm == null) return
        val yesterday = memory.today().minusDays(1)
        val journal = memory.journalFor(yesterday).trim()
        if (journal.isEmpty() || memory.hasSummary(yesterday)) return
        val msg =
            try {
                llm.chat(listOf(systemMessage(SUMMARY_PROMPT), userMessage(journal)))
            } catch (e: IOException) {
                log.warn("brain", "journal summary failed", e)
                return
            }
        val content = (msg["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val bullets = content.lines().filter { it.trim().startsWith("-") }
        memory.addDailySummary(yesterday, bullets.ifEmpty { listOf("(no summary produced)") })
    }
}
