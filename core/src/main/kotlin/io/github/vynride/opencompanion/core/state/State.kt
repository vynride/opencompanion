// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.state

enum class State(
    val wire: String,
) {
    SLEEPING("sleeping"),
    IDLE("idle"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    ERROR("error"),
    HAPPY("happy"),
    NOTICING("noticing"),
}
