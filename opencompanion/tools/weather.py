# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""weather: Open-Meteo, current conditions + next N hours."""

from __future__ import annotations

from datetime import datetime

import httpx

from opencompanion.tools import Tool, ToolContext

URL = "https://api.open-meteo.com/v1/forecast"

WMO = {
    0: "clear",
    1: "mostly clear",
    2: "partly cloudy",
    3: "overcast",
    45: "fog",
    48: "rime fog",
    51: "light drizzle",
    53: "drizzle",
    55: "heavy drizzle",
    61: "light rain",
    63: "rain",
    65: "heavy rain",
    71: "light snow",
    73: "snow",
    75: "heavy snow",
    80: "rain showers",
    81: "heavy showers",
    82: "violent showers",
    95: "thunderstorm",
    96: "thunderstorm with hail",
    99: "severe thunderstorm",
}


def words(code: int) -> str:
    return WMO.get(int(code), f"code {code}")


async def fetch_weather(http: httpx.AsyncClient, lat: float, lon: float, tz: str) -> dict:
    r = await http.get(
        URL,
        params={
            "latitude": lat,
            "longitude": lon,
            "timezone": tz,
            "forecast_days": 2,
            "current": "temperature_2m,weather_code,relative_humidity_2m",
            "hourly": "temperature_2m,precipitation_probability,weather_code",
        },
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def summarize(data: dict, hours: int = 6, now: datetime | None = None) -> str:
    cur = data["current"]
    out = [
        f"Now: {cur['temperature_2m']}°C, {words(cur['weather_code'])}, "
        f"humidity {cur['relative_humidity_2m']}%."
    ]

    h = data["hourly"]
    cutoff = (now or datetime.now()).replace(minute=0, second=0, microsecond=0)
    times = [datetime.fromisoformat(t) for t in h["time"]]
    start = next((i for i, t in enumerate(times) if t >= cutoff), len(times))

    parts = []
    for t, temp, pop, code in list(
        zip(h["time"], h["temperature_2m"], h["precipitation_probability"], h["weather_code"], strict=True)
    )[start : start + hours]:
        parts.append(f"{t[11:16]} {temp}°C {words(code)} ({pop}% rain)")
    out.append("Next hours: " + "; ".join(parts) + ".")
    return " ".join(out)


def make_tools(ctx: ToolContext) -> list[Tool]:
    lat = float(ctx.cfg.get("location.lat", 0.0))
    lon = float(ctx.cfg.get("location.lon", 0.0))
    tz = ctx.cfg.get("location.timezone", "UTC")

    async def weather() -> str:
        try:
            return summarize(await fetch_weather(ctx.http, lat, lon, tz))
        except (httpx.HTTPError, KeyError, ValueError) as e:
            return f"Weather unavailable: {e}"

    return [
        Tool(
            "weather",
            "Current weather and the next six hours at the configured location.",
            {"type": "object", "properties": {}},
            weather,
        )
    ]
