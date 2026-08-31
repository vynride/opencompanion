// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.state

import io.github.vynride.opencompanion.core.bus.Event
import io.github.vynride.opencompanion.core.bus.EventBus
import io.github.vynride.opencompanion.core.bus.Failure
import io.github.vynride.opencompanion.core.bus.PlaybackDone
import io.github.vynride.opencompanion.core.bus.Reply
import io.github.vynride.opencompanion.core.bus.Sense
import io.github.vynride.opencompanion.core.bus.SenseKind
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.core.bus.TimerDone
import io.github.vynride.opencompanion.core.bus.Transcript
import io.github.vynride.opencompanion.core.bus.Wake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class StateConfig(
    val idleToSleepS: Double = 300.0,
    val errorHoldS: Double = 3.0,
    val happyHoldS: Double = 1.0,
    val noticingHoldS: Double = 1.5,
    val followupS: Double = 6.0,
    val listenTimeoutS: Double = 14.0,
)

private val HOLD_STATES = setOf(State.HAPPY, State.ERROR, State.NOTICING)
private val WAKE_STATES = setOf(State.IDLE, State.SLEEPING, State.NOTICING)

/** The one place that decides state transitions. */
class StateMachine(
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val config: StateConfig,
) {
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var sleepJob: Job? = null
    private var holdJob: Job? = null
    private var followupJob: Job? = null
    private var beforeHold = State.IDLE
    private var wiring: Job? = null

    fun isArmed(): Boolean = _state.value in WAKE_STATES

    fun start() {
        wiring = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { handle(it) } }
        armSleepTimer()
    }

    fun stop() {
        wiring?.cancel()
        sleepJob?.cancel()
        holdJob?.cancel()
        followupJob?.cancel()
    }

    suspend fun handle(event: Event) {
        when (event) {
            is Wake -> onWake()
            is Transcript -> onTranscript(event)
            is Reply -> onReply()
            is PlaybackDone -> onPlaybackDone()
            is Failure -> onFailure()
            is Sense -> onSense(event)
            is TimerDone -> hold(State.HAPPY, config.happyHoldS)
            else -> Unit
        }
    }

    private suspend fun set(next: State) {
        if (next == _state.value) return
        _state.value = next
        armSleepTimer()
        bus.publish(StateChanged(next))
    }

    /** Run the idle countdown only while IDLE. */
    private fun armSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        if (_state.value != State.IDLE) return
        sleepJob =
            scope.launch {
                delay(config.idleToSleepS.seconds)
                // Detach before transitioning so the rearm inside set() cannot cancel this coroutine.
                sleepJob = null
                if (_state.value == State.IDLE) set(State.SLEEPING)
            }
    }

    private suspend fun hold(
        temp: State,
        seconds: Double,
        resume: State? = null,
    ) {
        holdJob?.cancel()
        if (resume == null && _state.value !in HOLD_STATES) beforeHold = _state.value
        set(temp)
        val back = resume ?: beforeHold
        holdJob =
            scope.launch {
                delay(seconds.seconds)
                if (_state.value == temp) set(back)
            }
    }

    private fun cancelFollowup() {
        followupJob?.cancel()
        followupJob = null
    }

    private fun armListenTimeout(seconds: Double) {
        cancelFollowup()
        followupJob =
            scope.launch {
                delay(seconds.seconds)
                if (_state.value == State.LISTENING) set(State.IDLE)
            }
    }

    private suspend fun onWake() {
        if (!isArmed()) return
        cancelFollowup()
        holdJob?.cancel()
        holdJob = null
        set(State.LISTENING)
        armListenTimeout(config.listenTimeoutS)
    }

    private suspend fun onTranscript(e: Transcript) {
        cancelFollowup()
        set(if (e.text.isBlank()) State.IDLE else State.THINKING)
    }

    private suspend fun onReply() = set(State.SPEAKING)

    /** Stay in LISTENING for the follow-up window after a reply, else go IDLE. */
    private suspend fun onPlaybackDone() {
        cancelFollowup()
        if (config.followupS > 0) {
            set(State.LISTENING)
            armListenTimeout(config.followupS)
        } else {
            set(State.IDLE)
        }
    }

    private suspend fun onFailure() {
        cancelFollowup()
        hold(State.ERROR, config.errorHoldS, resume = State.IDLE)
    }

    private suspend fun onSense(e: Sense) {
        if (e.kind == SenseKind.TOUCH) {
            hold(State.HAPPY, config.happyHoldS)
        } else if (_state.value == State.IDLE || _state.value == State.SLEEPING) {
            hold(State.NOTICING, config.noticingHoldS, resume = State.IDLE)
        }
    }
}
