// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.tools

import io.github.vynride.opencompanion.core.ports.Camera
import io.github.vynride.opencompanion.core.ports.Lens
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive

const val LOOK_DESC = "Take a photo with the phone camera and answer a question about what is visible."

private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

fun lookTool(
    camera: Camera?,
    askVision: suspend (String, ByteArray) -> String,
): List<Tool> {
    if (camera == null) {
        return listOf(disabledTool("look", LOOK_DESC, "camera not available"))
    }
    return listOf(
        Tool(
            "look",
            LOOK_DESC,
            objectSchema(
                "question" to stringParam(),
                "camera" to enumParam("front", "back"),
                required = listOf("question"),
            ),
        ) { args ->
            val question = args["question"]!!.jsonPrimitive.content
            val lens = if (args["camera"]?.jsonPrimitive?.content == "back") Lens.BACK else Lens.FRONT
            var jpeg = ByteArray(0)
            // termux-camera-photo can return truncated or non-JPEG bytes; retry a few times.
            for (attempt in 0 until 3) {
                jpeg = camera.captureJpeg(lens)
                if (isJpeg(jpeg)) break
                delay(400)
            }
            if (!isJpeg(jpeg)) {
                "The camera didn't return a usable image; please try again."
            } else {
                askVision(question, jpeg)
            }
        },
    )
}
