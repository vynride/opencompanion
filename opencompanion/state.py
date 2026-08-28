# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""State enum and the one place that decides transitions."""

from __future__ import annotations

import asyncio
import logging
from enum import StrEnum

from opencompanion.bus import (
    Error,
    EventBus,
    PlaybackDone,
    Reply,
    Sense,
    StateChanged,
    TimerDone,
    Transcript,
    Wake,
)

log = logging.getLogger("opencompanion.state")


class State(StrEnum):
    SLEEPING = "sleeping"
    IDLE = "idle"
    LISTENING = "listening"
    THINKING = "thinking"
    SPEAKING = "speaking"
    ERROR = "error"
    HAPPY = "happy"
    NOTICING = "noticing"


# A hold entered from one of these must not overwrite the state to resume.
_HOLD_STATES = (State.HAPPY, State.ERROR, State.NOTICING)

# States in which a Wake event is acted on.
_WAKE_STATES = (State.IDLE, State.SLEEPING, State.NOTICING)


class StateMachine:
    def __init__(
        self,
        bus: EventBus,
        *,
        idle_to_sleep_s: float = 300,
        error_hold_s: float = 3,
        happy_hold_s: float = 1,
        noticing_hold_s: float = 1.5,
        followup_s: float = 0.0,
        listen_timeout_s: float = 14.0,
    ) -> None:
        self.bus = bus
        self.state = State.IDLE
        self.idle_to_sleep_s = idle_to_sleep_s
        self.error_hold_s = error_hold_s
        self.happy_hold_s = happy_hold_s
        self.noticing_hold_s = noticing_hold_s
        self.followup_s = followup_s
        self.listen_timeout_s = listen_timeout_s
        self._sleep_task: asyncio.Task | None = None
        self._sleep_at: float = 0.0
        self._hold_task: asyncio.Task | None = None
        self._followup_task: asyncio.Task | None = None
        self._before_hold: State = State.IDLE
        self._unsubs: list = []

    def is_armed(self) -> bool:
        """True when a Wake event will be acted on."""
        return self.state in _WAKE_STATES

    def start(self) -> None:
        b = self.bus
        self._unsubs = [
            b.subscribe(Wake, self._on_wake),
            b.subscribe(Transcript, self._on_transcript),
            b.subscribe(Reply, self._on_reply),
            b.subscribe(PlaybackDone, self._on_playback_done),
            b.subscribe(Error, self._on_error),
            b.subscribe(Sense, self._on_sense),
            b.subscribe(TimerDone, lambda e: self._hold(State.HAPPY, self.happy_hold_s)),
        ]
        self._note_activity()

    def stop(self) -> None:
        for u in self._unsubs:
            u()
        self._unsubs = []
        for t in (self._sleep_task, self._hold_task, self._followup_task):
            if t:
                t.cancel()

    async def set(self, new: State) -> None:
        if new == self.state:
            return
        log.info("state %s -> %s", self.state.value, new.value)
        self.state = new
        self._arm_sleep_timer()
        await self.bus.publish(StateChanged(new))

    def _note_activity(self) -> None:
        """Push the idle->sleep deadline out by `idle_to_sleep_s`."""
        self._sleep_at = asyncio.get_running_loop().time() + self.idle_to_sleep_s
        self._arm_sleep_timer()

    def _arm_sleep_timer(self) -> None:
        """Run the idle countdown only while IDLE; the deadline itself is unchanged."""
        # never cancel the countdown task from inside itself
        if self._sleep_task and self._sleep_task is not asyncio.current_task():
            self._sleep_task.cancel()
        self._sleep_task = None
        if self.state == State.IDLE:
            self._sleep_task = asyncio.get_running_loop().create_task(self._sleep_later())

    async def _sleep_later(self) -> None:
        loop = asyncio.get_running_loop()
        while (remaining := self._sleep_at - loop.time()) > 0:
            await asyncio.sleep(remaining)
        if self.state == State.IDLE:
            await self.set(State.SLEEPING)

    async def _hold(
        self, temp: State, seconds: float, *, resume: State | None = None, rearm_sleep: bool = False
    ) -> None:
        """Move to a temporary state for `seconds`, then to `resume` (default: the
        state before the hold). `rearm_sleep` restarts the idle countdown."""
        if self._hold_task:
            self._hold_task.cancel()
        if resume is None and self.state not in _HOLD_STATES:
            self._before_hold = self.state
        await self.set(temp)
        back = resume if resume is not None else self._before_hold
        self._hold_task = asyncio.get_running_loop().create_task(
            self._release(seconds, temp, back, rearm_sleep)
        )

    async def _release(self, seconds: float, temp: State, back: State, rearm_sleep: bool) -> None:
        await asyncio.sleep(seconds)
        if self.state == temp:
            if rearm_sleep:
                self._note_activity()
            await self.set(back)

    def _cancel_followup(self) -> None:
        if self._followup_task:
            self._followup_task.cancel()
            self._followup_task = None

    async def _on_playback_done(self, e: PlaybackDone) -> None:
        """Stay in LISTENING for `followup_s` after a reply, else go IDLE."""
        self._cancel_followup()
        if self.followup_s > 0:
            await self.set(State.LISTENING)
            self._arm_listen_timeout(self.followup_s)
        else:
            await self.set(State.IDLE)

    def _arm_listen_timeout(self, seconds: float) -> None:
        self._cancel_followup()
        self._followup_task = asyncio.get_running_loop().create_task(self._listen_timeout(seconds))

    async def _listen_timeout(self, seconds: float) -> None:
        await asyncio.sleep(seconds)
        if self.state == State.LISTENING:
            await self.set(State.IDLE)

    async def _on_wake(self, e: Wake) -> None:
        if not self.is_armed():
            return
        self._note_activity()
        self._cancel_followup()
        if self._hold_task and not self._hold_task.done():
            self._hold_task.cancel()
            self._hold_task = None
        await self.set(State.LISTENING)
        self._arm_listen_timeout(self.listen_timeout_s)

    async def _on_reply(self, e: Reply) -> None:
        self._note_activity()
        await self.set(State.SPEAKING)

    async def _on_transcript(self, e: Transcript) -> None:
        self._note_activity()
        self._cancel_followup()
        await self.set(State.THINKING if e.text.strip() else State.IDLE)

    async def _on_error(self, e: Error) -> None:
        self._cancel_followup()
        await self._hold(State.ERROR, self.error_hold_s, resume=State.IDLE)

    async def _on_sense(self, e: Sense) -> None:
        if e.kind == "touch":
            self._note_activity()
            await self._hold(State.HAPPY, self.happy_hold_s)
        elif self.state in (State.IDLE, State.SLEEPING):
            await self._hold(State.NOTICING, self.noticing_hold_s, resume=State.IDLE, rearm_sleep=True)
