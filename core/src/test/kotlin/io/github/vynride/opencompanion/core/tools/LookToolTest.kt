// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.FakeCamera
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LookToolTest {
    @Test
    fun `look takes a photo and asks the vision model`() = runTest {
        val camera = FakeCamera()
        var seenQuestion = ""
        var seenBytes = ByteArray(0)
        val ask: suspend (String, ByteArray) -> String = { q, data ->
            seenQuestion = q
            seenBytes = data
            "a grey wall"
        }
        val tools = lookTool(camera, ask)
        val result = tools.first().call(buildJsonObject { put("question", "what do you see?") })
        assertEquals("a grey wall", result)
        assertEquals("what do you see?", seenQuestion)
        assertEquals(0xFF.toByte(), seenBytes[0])
        assertEquals(0xD8.toByte(), seenBytes[1])
        assertEquals(1, camera.captures)
    }

    @Test
    fun `look retries then gives up on an unusable image`() = runTest {
        val camera = FakeCamera(jpeg = byteArrayOf(0, 0, 0))
        val ask: suspend (String, ByteArray) -> String = { _, _ -> "should not be called" }
        val tools = lookTool(camera, ask)
        val result = tools.first().call(buildJsonObject { put("question", "what do you see?") })
        assertEquals("The camera didn't return a usable image; please try again.", result)
        assertEquals(3, camera.captures)
    }

    @Test
    fun `look disabled without a camera`() = runTest {
        val tools = lookTool(null) { _, _ -> "unused" }
        assertEquals("look", tools.first().name)
        assertTrue(tools.first().call(buildJsonObject { put("question", "x") }).contains("disabled"))
    }
}
