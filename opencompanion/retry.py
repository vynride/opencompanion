# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Retry policy shared by the HTTP clients."""

from __future__ import annotations

import httpx

# 404 included: some hosted backends return it transiently while a model loads.
RETRY_STATUSES = {404, 429, 500, 502, 503}
ATTEMPTS = 3
BACKOFF_BASE = 0.5
MAX_DELAY = 2.5


def retry_delay(r: httpx.Response, attempt: int) -> float:
    """Seconds to wait before retrying `r`: its Retry-After if given, else a short backoff."""
    v = r.headers.get("retry-after")
    if v:
        try:
            return min(float(v), MAX_DELAY)
        except ValueError:
            pass
    return BACKOFF_BASE * attempt


def should_retry(r: httpx.Response, attempt: int) -> bool:
    return r.status_code in RETRY_STATUSES and attempt < ATTEMPTS
