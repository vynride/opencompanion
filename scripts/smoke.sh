#!/data/data/com.termux/files/usr/bin/bash
# Smoke test: run on the phone after install-phone.sh. Checks what the install
# set up (imports, audio, face server) and prints PASS/FAIL per check.
# Exits non-zero if anything failed.
set -euo pipefail
cd "${OC_REPO_DIR:-$HOME/opencompanion}"

FAILED=0

check() {
  local name="$1"
  shift
  if "$@"; then
    echo "PASS: $name"
  else
    echo "FAIL: $name"
    FAILED=1
  fi
}

check_imports() {
  python -c "import onnxruntime, openwakeword, numpy, aiohttp, httpx" >/dev/null 2>&1
}

check_pulse_source() {
  pactl list short sources 2>/dev/null | grep -q OpenSL
}

check_mic_capture() {
  local raw
  raw="$(mktemp)"
  timeout 2 parec -d OpenSL_ES_source --raw --format=s16le --rate=16000 --channels=1 > "$raw" 2>/dev/null || true
  python -c "
import struct
import sys

data = open('$raw', 'rb').read()
n = len(data) // 2
if n == 0:
    sys.exit(1)
samples = struct.unpack(f'<{n}h', data[: n * 2])
rms = (sum(s * s for s in samples) / n) ** 0.5
sys.exit(0 if rms > 0 else 1)
"
  local rc=$?
  rm -f "$raw"
  return $rc
}

check_face_server() {
  local port="8080"
  if [ -f config.yaml ]; then
    port="$(python -c "
import yaml
cfg = yaml.safe_load(open('config.yaml')) or {}
print((cfg.get('face') or {}).get('port', 8080))
" 2>/dev/null || echo 8080)"
  fi
  # Python rather than curl: a partial mirror upgrade can leave Termux's curl ABI-broken.
  python -c "
import sys, urllib.request
try:
    urllib.request.urlopen('http://localhost:$port/', timeout=3).read(1)
except Exception:
    sys.exit(1)
" >/dev/null 2>&1
}

check "python imports (onnxruntime, openwakeword, numpy, aiohttp, httpx)" check_imports
check "PulseAudio OpenSL source present" check_pulse_source
check "mic capture produces non-zero audio" check_mic_capture
check "face server reachable" check_face_server

exit $FAILED
