# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Configuration: config.yaml layered over config.example.yaml, plus secrets from .env."""

from __future__ import annotations

import functools
import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from dotenv import dotenv_values

_EXAMPLE_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.example.yaml"


@dataclass(frozen=True)
class Service:
    """One OpenAI-compatible endpoint: where to send requests, how to authenticate, which model."""

    base_url: str
    api_key: str
    model: str
    auth_header: str = "authorization"

    def url(self, path: str) -> str:
        # A query string on base_url is carried over to every request.
        base, _, query = self.base_url.partition("?")
        url = f"{base.rstrip('/')}/{path.lstrip('/')}"
        return f"{url}?{query}" if query else url

    def headers(self) -> dict[str, str]:
        # auth_header: "authorization" (Bearer) or "api-key"
        if self.auth_header == "api-key":
            return {"api-key": self.api_key}
        return {"Authorization": f"Bearer {self.api_key}"}


@functools.lru_cache(maxsize=1)
def _defaults() -> dict:
    return yaml.safe_load(_EXAMPLE_CONFIG_PATH.read_text()) or {}


def _merge(base: dict, override: dict) -> dict:
    out = dict(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Config:
    def __init__(self, data: dict, env: Mapping[str, str], root: Path | None = None):
        self._data = _merge(_defaults(), data or {})
        self._env = dict(env)
        self.root = root or Path.cwd()

    def get(self, path: str, default: Any = None) -> Any:
        node: Any = self._data
        for part in path.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def key(self, name: str) -> str | None:
        value = self._env.get(name, "")
        return value or None

    def service(self, name: str) -> Service | None:
        """The endpoint for `name` ("chat" | "transcribe" | "tts"), or None if
        it has no API key or no model. Per-service `openai.<name>_*` keys and
        `OPENAI_<NAME>_API_KEY` override the shared `openai.*` / `OPENAI_API_KEY`.
        """
        key = self.key(f"OPENAI_{name.upper()}_API_KEY") or self.key("OPENAI_API_KEY")
        model = self.get(f"openai.{name}_model") or ""
        if not key or not model:
            return None
        return Service(
            base_url=self.get(f"openai.{name}_base_url") or self.get("openai.base_url") or "",
            api_key=key,
            model=model,
            auth_header=self.get(f"openai.{name}_auth_header")
            or self.get("openai.auth_header")
            or "authorization",
        )


def load_config(path: str | Path = "config.yaml", env_path: str | Path = ".env") -> Config:
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(f"{path} not found; copy config.example.yaml to {path.name} and edit it")
    data = yaml.safe_load(path.read_text()) or {}
    env: dict[str, str] = {}
    env_path = Path(env_path)
    if env_path.exists():
        env.update({k: v or "" for k, v in dotenv_values(env_path).items()})
    env.update(os.environ)
    return Config(data, env, root=path.resolve().parent)
