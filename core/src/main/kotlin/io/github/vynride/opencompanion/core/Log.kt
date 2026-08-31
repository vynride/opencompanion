// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core

enum class Level { DEBUG, INFO, WARN, ERROR }

fun interface Log {
    fun log(
        level: Level,
        tag: String,
        message: String,
        error: Throwable?,
    )

    fun info(
        tag: String,
        message: String,
    ) = log(Level.INFO, tag, message, null)

    fun warn(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = log(Level.WARN, tag, message, error)

    fun error(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = log(Level.ERROR, tag, message, error)

    fun debug(
        tag: String,
        message: String,
    ) = log(Level.DEBUG, tag, message, null)

    object Stdout : Log {
        override fun log(
            level: Level,
            tag: String,
            message: String,
            error: Throwable?,
        ) {
            println("$level $tag: $message" + (error?.let { " (${it.message})" } ?: ""))
        }
    }
}
