# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
from pathlib import Path

import pytest

from opencompanion.config import Config, Service, load_config

# The tunables the code reads (per-service openai.* overrides aside). config.example.yaml must define all of
# them, so a fresh install never silently falls back to a hidden default.
DOCUMENTED_KEYS = [
    "companion.name",
    "location.name",
    "location.lat",
    "location.lon",
    "location.timezone",
    "face.host",
    "face.port",
    "face.open_browser",
    "face.browser_package",
    "face.tap_delay_s",
    "face.tap_xy",
    "audio.rate",
    "audio.frame_ms",
    "audio.capture_command",
    "audio.pulse_start",
    "audio.silence_restart_s",
    "wakeword.model",
    "wakeword.threshold",
    "wakeword.refractory_s",
    "wakeword.vad_gate",
    "wakeword.gate_hold_s",
    "wakeword.preroll_ms",
    "wakeword.max_queue",
    "openai.base_url",
    "openai.auth_header",
    "openai.chat_model",
    "openai.transcribe_model",
    "openai.tts_model",
    "brain.api",
    "stt.language",
    "stt.vad_aggressiveness",
    "stt.silence_ms",
    "stt.max_ms",
    "stt.min_ms",
    "brain.reasoning_effort",
    "brain.vision",
    "brain.max_turns",
    "brain.session_reset_min",
    "brain.max_tool_calls",
    "brain.filler_after_s",
    "brain.fillers",
    "tts.voice",
    "tts.speed",
    "tts.instructions",
    "state.idle_to_sleep_s",
    "state.error_hold_s",
    "state.happy_hold_s",
    "state.noticing_hold_s",
    "state.followup_s",
    "state.listen_timeout_s",
    "senses.poll_s",
    "senses.tap_threshold",
    "senses.dark_lux",
    "senses.proximity_near_cm",
    "senses.dim_brightness",
    "senses.normal_brightness",
    "senses.sleep_brightness",
    "senses.touch_device",
    "senses.sensor_names",
    "memory.dir",
    "memory.max_facts_lines",
    "hosts.laptop",
    "hosts.timeout_s",
    "look.max_px",
    "search.max_results",
    "search.max_chars",
]


def test_get_dotted_path_and_default():
    cfg = Config({"openai": {"chat_model": "m1"}}, {})
    assert cfg.get("openai.chat_model") == "m1"
    assert cfg.get("openai.missing", 7) == 7
    assert cfg.get("nope.deep") is None


def test_defaults_apply_when_missing():
    cfg = Config({}, {})
    assert cfg.get("stt.silence_ms") == 700
    assert cfg.get("state.idle_to_sleep_s") == 300


def test_key_reads_env_and_treats_empty_as_missing():
    cfg = Config({}, {"OPENAI_API_KEY": "abc", "EXA_API_KEY": ""})
    assert cfg.key("OPENAI_API_KEY") == "abc"
    assert cfg.key("EXA_API_KEY") is None
    assert cfg.key("MISSING_KEY") is None


def test_load_config_reads_yaml_and_dotenv(tmp_path: Path, monkeypatch):
    (tmp_path / "config.yaml").write_text("brain:\n  reasoning_effort: from-yaml\n")
    (tmp_path / ".env").write_text("OPENAI_API_KEY=fromdotenv\n")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    cfg = load_config(tmp_path / "config.yaml", tmp_path / ".env")
    assert cfg.get("brain.reasoning_effort") == "from-yaml"
    assert cfg.key("OPENAI_API_KEY") == "fromdotenv"
    assert cfg.root == tmp_path


def test_load_config_missing_file_mentions_example(tmp_path: Path):
    with pytest.raises(FileNotFoundError, match="config.example.yaml"):
        load_config(tmp_path / "config.yaml", tmp_path / ".env")


def test_example_config_parses_with_placeholders_only():
    repo = Path(__file__).resolve().parent.parent
    cfg = load_config(repo / "config.example.yaml", repo / "nonexistent.env")
    assert cfg.get("location.lat") == 0.0
    assert cfg.get("location.lon") == 0.0
    assert cfg.get("hosts.laptop") == "192.0.2.10:22"


@pytest.mark.parametrize("dotted_key", DOCUMENTED_KEYS)
def test_example_config_has_every_documented_key(dotted_key):
    repo = Path(__file__).resolve().parent.parent
    cfg = load_config(repo / "config.example.yaml", repo / "nonexistent.env")
    sentinel = object()
    assert cfg.get(dotted_key, sentinel) is not sentinel, (
        f"config.example.yaml is missing a default for {dotted_key!r}"
    )


def test_service_uses_per_service_override_then_falls_back():
    cfg = Config(
        {
            "openai": {
                "base_url": "https://main.example/v1",
                "chat_model": "c",
                "tts_model": "t",
                "tts_base_url": "https://other.example/v1",
                "tts_auth_header": "api-key",
            }
        },
        {"OPENAI_API_KEY": "mainkey", "OPENAI_TTS_API_KEY": "ttskey"},
    )
    tts = cfg.service("tts")
    assert (tts.base_url, tts.api_key, tts.model, tts.auth_header) == (
        "https://other.example/v1",
        "ttskey",
        "t",
        "api-key",
    )
    assert tts.url("audio/speech") == "https://other.example/v1/audio/speech"
    assert tts.headers() == {"api-key": "ttskey"}
    chat = cfg.service("chat")
    assert (chat.base_url, chat.api_key, chat.model) == ("https://main.example/v1", "mainkey", "c")
    assert chat.headers() == {"Authorization": "Bearer mainkey"}


def test_service_url_keeps_a_query_string_from_base_url():
    svc = Service("https://h/x?v=1", "k", "m")
    assert svc.url("audio/transcriptions") == "https://h/x/audio/transcriptions?v=1"


def test_service_is_none_without_key_or_model():
    assert Config({"openai": {"chat_model": "c"}}, {}).service("chat") is None
    assert Config({}, {"OPENAI_API_KEY": "k"}).service("chat") is None
    assert Config({"openai": {"chat_model": "c"}}, {"OPENAI_API_KEY": "k"}).service("chat") is not None
