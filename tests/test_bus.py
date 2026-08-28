# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later


from opencompanion.bus import EventBus, Reply, Transcript, Wake


async def test_publish_calls_async_and_sync_handlers():
    bus = EventBus()
    seen = []
    bus.subscribe(Transcript, lambda e: seen.append(("sync", e.text)))

    async def h(e):
        seen.append(("async", e.text))

    bus.subscribe(Transcript, h)
    await bus.publish(Transcript("hi"))
    assert seen == [("sync", "hi"), ("async", "hi")]


async def test_handlers_only_get_their_type():
    bus = EventBus()
    seen = []
    bus.subscribe(Wake, lambda e: seen.append(e))
    await bus.publish(Reply("x"))
    assert seen == []


async def test_unsubscribe():
    bus = EventBus()
    seen = []
    unsub = bus.subscribe(Wake, lambda e: seen.append(e))
    unsub()
    await bus.publish(Wake())
    assert seen == []


async def test_handler_exception_is_swallowed(caplog):
    bus = EventBus()

    def bad(e):
        raise RuntimeError("boom")

    seen = []
    bus.subscribe(Wake, bad)
    bus.subscribe(Wake, lambda e: seen.append(1))
    await bus.publish(Wake())
    assert seen == [1]
    assert "boom" in caplog.text
