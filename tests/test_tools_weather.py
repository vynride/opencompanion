# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime

import httpx
import respx

from opencompanion.bus import EventBus
from opencompanion.config import Config
from opencompanion.tools import ToolContext
from opencompanion.tools.weather import make_tools, summarize

SAMPLE = {
    "current": {"temperature_2m": 21.5, "weather_code": 3, "relative_humidity_2m": 60},
    "hourly": {
        "time": [f"2026-08-28T{h:02d}:00" for h in range(12, 20)],
        "temperature_2m": [21, 22, 23, 23, 22, 21, 20, 19],
        "precipitation_probability": [0, 0, 10, 40, 60, 60, 20, 0],
        "weather_code": [3, 3, 3, 61, 61, 61, 3, 1],
    },
}


def _sample_with_past_hours():
    # Spans 08:00-21:00 so the window can start part-way into the fixture.
    hours = list(range(8, 22))
    return {
        "current": {"temperature_2m": 21.5, "weather_code": 3, "relative_humidity_2m": 60},
        "hourly": {
            "time": [f"2026-08-28T{h:02d}:00" for h in hours],
            "temperature_2m": [18 + (h % 5) for h in hours],
            "precipitation_probability": [0, 0, 0, 0, 10, 40, 60, 60, 20, 0, 0, 0, 0, 0],
            "weather_code": [3, 3, 3, 3, 3, 61, 61, 61, 3, 1, 1, 1, 1, 1],
        },
    }


def test_summarize_now_and_next_hours():
    text = summarize(SAMPLE, hours=6, now=datetime(2026, 8, 28, 12, 0))
    assert text.startswith("Now: 21.5°C, overcast, humidity 60%.")
    assert "rain" in text.lower()
    assert "60%" in text
    assert "19:00" not in text  # only 6 hours


def test_summarize_starts_at_now_truncated_to_the_hour_not_start_of_day():
    data = _sample_with_past_hours()
    text = summarize(data, hours=6, now=datetime(2026, 8, 28, 12, 30))
    assert "12:00" in text
    assert "17:00" in text
    assert "08:00" not in text
    assert "11:00" not in text
    assert "18:00" not in text


async def test_weather_tool_hits_open_meteo_with_config_coords():
    with respx.mock:
        route = respx.get("https://api.open-meteo.com/v1/forecast").mock(
            return_value=httpx.Response(200, json=SAMPLE)
        )
        cfg = Config({"location": {"lat": 1.5, "lon": 2.5, "timezone": "UTC"}}, {})
        ctx = ToolContext(cfg=cfg, bus=EventBus(), http=httpx.AsyncClient())
        tool = make_tools(ctx)[0]
        text = await tool.func()
        assert "Now:" in text
        params = route.calls[0].request.url.params
        assert params["latitude"] == "1.5" and params["longitude"] == "2.5"


async def test_weather_tool_reports_failure():
    with respx.mock:
        respx.get("https://api.open-meteo.com/v1/forecast").mock(return_value=httpx.Response(500))
        ctx = ToolContext(cfg=Config({}, {}), bus=EventBus(), http=httpx.AsyncClient())
        text = await make_tools(ctx)[0].func()
        assert text.startswith("Weather unavailable")
