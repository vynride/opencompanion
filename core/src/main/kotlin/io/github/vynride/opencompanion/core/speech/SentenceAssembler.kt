// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.speech

private const val TERMINATORS = ".!?…"
private const val MIN_SENTENCE_CHARS = 2

/**
 * Collects text deltas into sentences. A sentence ends at `.` `!` `?` `…` followed by
 * whitespace; the trailing end can only be claimed by [flush], since more text may still
 * arrive. Deliberately dumb: replies are prompted to be one short sentence.
 */
class SentenceAssembler {
    private val buf = StringBuilder()

    /** Sentences completed by this delta, in order. */
    fun push(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        var start = 0
        for (i in 0 until buf.length - 1) {
            if (buf[i] !in TERMINATORS || !buf[i + 1].isWhitespace()) continue
            val sentence = buf.substring(start, i + 1).trim()
            // A bare terminator is punctuation noise, not a sentence; leave it for the next split.
            if (sentence.length < MIN_SENTENCE_CHARS) continue
            out += sentence
            start = i + 1
        }
        if (start > 0) buf.deleteRange(0, start)
        return out
    }

    /** The unemitted remainder; leaves the assembler empty. */
    fun flush(): String {
        val rest = buf.toString().trim()
        buf.setLength(0)
        return rest
    }
}
