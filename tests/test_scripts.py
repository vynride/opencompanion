# Copyright (C) 2026 Vivian Richard Demello (vynride)
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Static checks on the shell scripts; they are parsed, never run."""

import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"
EXPECTED_NAMES = ["install-phone.sh", "sync.sh", "opencompanion-service.sh", "smoke.sh"]
SCRIPTS = [SCRIPTS_DIR / name for name in EXPECTED_NAMES]


def test_expected_scripts_exist():
    names = {p.name for p in SCRIPTS_DIR.glob("*.sh")}
    assert set(EXPECTED_NAMES) <= names


@pytest.mark.parametrize("path", SCRIPTS, ids=EXPECTED_NAMES)
def test_script_parses_with_bash(path):
    subprocess.run(["bash", "-n", str(path)], check=True)


@pytest.mark.parametrize("path", SCRIPTS, ids=EXPECTED_NAMES)
def test_script_is_executable(path):
    assert path.stat().st_mode & 0o111, f"{path.name} is not executable"


@pytest.mark.parametrize("path", SCRIPTS, ids=EXPECTED_NAMES)
def test_script_has_strict_mode(path):
    assert re.search(r"^set -eu", path.read_text(), re.MULTILINE), f"{path.name} is missing set -eu"
