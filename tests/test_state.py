# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
import asyncio

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
from opencompanion.state import State, StateMachine


def make(**kw):
    bus = EventBus()
    sm = StateMachine(
        bus,
        idle_to_sleep_s=kw.get("sleep", 0.05),
        error_hold_s=kw.get("err", 0.02),
        happy_hold_s=kw.get("happy", 0.02),
        noticing_hold_s=kw.get("noticing", 0.02),
        followup_s=kw.get("followup", 0.0),
        listen_timeout_s=kw.get("listen_timeout", 10.0),
    )
    changes = []
    bus.subscribe(StateChanged, lambda e: changes.append(e.state))
    sm.start()
    return bus, sm, changes


async def test_full_turn():
    bus, sm, changes = make()
    await bus.publish(Wake())
    await bus.publish(Transcript("hello"))
    await bus.publish(Reply("hi"))
    await bus.publish(PlaybackDone())
    assert changes == [State.LISTENING, State.THINKING, State.SPEAKING, State.IDLE]
    sm.stop()


async def test_playback_done_opens_a_followup_window_then_returns_to_idle():
    bus, sm, changes = make(sleep=1, followup=0.05)
    await bus.publish(Reply("hi"))
    await bus.publish(PlaybackDone())
    assert sm.state == State.LISTENING
    await asyncio.sleep(0.08)
    assert sm.state == State.IDLE
    assert changes == [State.SPEAKING, State.LISTENING, State.IDLE]
    sm.stop()


async def test_transcript_in_followup_window_cancels_the_timeout():
    bus, sm, changes = make(sleep=1, followup=0.05)
    await bus.publish(Reply("hi"))
    await bus.publish(PlaybackDone())
    assert sm.state == State.LISTENING
    await bus.publish(Transcript("more please"))
    assert sm.state == State.THINKING
    await asyncio.sleep(0.08)  # past the follow-up window
    assert sm.state == State.THINKING
    sm.stop()


async def test_followup_disabled_goes_straight_to_idle():
    bus, sm, changes = make(sleep=1, followup=0.0)
    await bus.publish(Reply("hi"))
    await bus.publish(PlaybackDone())
    assert sm.state == State.IDLE
    sm.stop()


async def test_empty_transcript_returns_to_idle():
    bus, sm, changes = make()
    await bus.publish(Wake())
    await bus.publish(Transcript(""))
    assert sm.state == State.IDLE
    sm.stop()


async def test_wake_ignored_while_busy():
    bus, sm, changes = make()
    await bus.publish(Wake())
    await bus.publish(Transcript("x"))
    await bus.publish(Wake())
    assert sm.state == State.THINKING
    assert not sm.is_armed()
    sm.stop()


async def test_wake_ignored_while_speaking():
    bus, sm, changes = make()
    await bus.publish(Reply("hi"))
    assert sm.state == State.SPEAKING
    await bus.publish(Wake())
    assert sm.state == State.SPEAKING
    assert not sm.is_armed()
    sm.stop()


async def test_error_holds_then_idle():
    # A long idle_to_sleep_s keeps the idle->sleep timer out of the way.
    bus, sm, changes = make(sleep=1)
    await bus.publish(Error("speech", "timeout"))
    assert sm.state == State.ERROR
    await asyncio.sleep(0.05)
    assert sm.state == State.IDLE
    sm.stop()


async def test_touch_is_happy_then_previous():
    bus, sm, changes = make()
    await bus.publish(Wake())
    await bus.publish(Sense("touch", 1))
    assert sm.state == State.HAPPY
    await asyncio.sleep(0.05)
    assert sm.state == State.LISTENING
    sm.stop()


async def test_timer_done_is_happy():
    bus, sm, changes = make()
    await bus.publish(TimerDone("tea"))
    assert sm.state == State.HAPPY
    sm.stop()


async def test_idle_sleeps_and_sense_wakes():
    bus, sm, changes = make()
    await asyncio.sleep(0.1)
    assert sm.state == State.SLEEPING
    assert sm.is_armed()
    await bus.publish(Sense("proximity", 0.0))
    assert sm.state == State.NOTICING
    await asyncio.sleep(0.05)
    assert sm.state == State.IDLE
    sm.stop()


async def test_senses_keep_the_machine_awake_then_it_sleeps_when_they_stop():
    bus, sm, changes = make(sleep=0.06, noticing=0.01)
    for value in (0.0, 5.0, 0.0, 5.0):  # a changing reading, every 30ms
        await bus.publish(Sense("proximity", value))
        await asyncio.sleep(0.03)
    assert State.SLEEPING not in changes  # 120ms elapsed, twice idle_to_sleep_s
    assert sm.state == State.IDLE
    await asyncio.sleep(0.1)  # now quiet
    assert sm.state == State.SLEEPING
    sm.stop()


async def test_an_error_hold_does_not_re_arm_the_sleep_timer():
    bus, sm, changes = make(sleep=0.06, err=0.01)
    await asyncio.sleep(0.04)
    await bus.publish(Error("speech", "timeout"))  # ERROR, back to IDLE at ~50ms
    await asyncio.sleep(0.05)
    assert sm.state == State.SLEEPING  # the original deadline still stands
    sm.stop()


async def test_touch_re_arms_the_sleep_timer():
    bus, sm, changes = make(sleep=0.06, happy=0.01)
    await asyncio.sleep(0.04)
    await bus.publish(Sense("touch", 1))
    await asyncio.sleep(0.04)  # past the original deadline, short of the new one
    assert sm.state == State.IDLE
    assert State.SLEEPING not in changes
    sm.stop()


async def test_wake_from_sleeping_goes_listening():
    bus, sm, changes = make()
    await sm.set(State.SLEEPING)
    await bus.publish(Wake())
    assert sm.state == State.LISTENING
    sm.stop()


async def test_sense_in_idle_becomes_noticing_then_idle():
    bus, sm, changes = make()
    assert sm.state == State.IDLE
    await bus.publish(Sense("proximity", 0.0))
    assert sm.state == State.NOTICING
    assert sm.is_armed()
    await asyncio.sleep(0.05)
    assert sm.state == State.IDLE
    sm.stop()


async def test_wake_from_noticing_goes_listening():
    bus, sm, changes = make()
    await bus.publish(Sense("proximity", 0.0))
    assert sm.state == State.NOTICING
    await bus.publish(Wake())
    assert sm.state == State.LISTENING
    sm.stop()


async def test_wake_cancels_noticing_hold():
    bus, sm, changes = make()
    await bus.publish(Sense("proximity", 0.0))
    assert sm.state == State.NOTICING
    await bus.publish(Wake())
    assert sm.state == State.LISTENING
    await asyncio.sleep(0.05)
    assert sm.state == State.LISTENING
    sm.stop()


async def test_wake_listening_falls_back_to_idle_if_no_transcript():
    bus, sm, changes = make(sleep=5, listen_timeout=0.05)
    await bus.publish(Wake())
    assert sm.state == State.LISTENING
    await asyncio.sleep(0.12)
    assert sm.state == State.IDLE
    sm.stop()


async def test_wake_listening_watchdog_cancelled_by_transcript():
    bus, sm, changes = make(sleep=5, listen_timeout=0.05)
    await bus.publish(Wake())
    await bus.publish(Transcript("hello"))
    assert sm.state == State.THINKING
    await asyncio.sleep(0.12)
    assert sm.state == State.THINKING  # watchdog did not fire
    sm.stop()


async def test_sleeping_transition_lets_async_subscribers_finish():
    # set(SLEEPING) runs inside the countdown task; re-arming the timer must not
    # cancel it mid-publish, or async subscribers never finish.
    bus, sm, changes = make()
    done = []

    async def slow(e):
        if e.state == State.SLEEPING:
            await asyncio.sleep(0.02)
            done.append(e.state)

    bus.subscribe(StateChanged, slow)
    await asyncio.sleep(0.15)
    assert sm.state == State.SLEEPING
    assert done == [State.SLEEPING]
    sm.stop()
