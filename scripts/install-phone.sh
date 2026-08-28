#!/data/data/com.termux/files/usr/bin/bash
# Installs everything the companion needs inside Termux. Idempotent. Run as the Termux
# user, either on the phone or over ssh:
#   ssh "$OC_HOST" 'bash -s' < scripts/install-phone.sh
set -euo pipefail

# Under `bash -s` over ssh no Termux profile is sourced, so default these.
# Android has no writable system-wide /tmp; TMPDIR goes in the Termux prefix.
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"
export PREFIX HOME TMPDIR

REPO_DIR="${OC_REPO_DIR:-$HOME/opencompanion}"

echo "== packages"
export DEBIAN_FRONTEND=noninteractive   # never prompt over a non-tty ssh
APT_OPTS=(-o Dpkg::Options::=--force-confold)
# apt, not the `pkg` wrapper: pkg's mirror check needs curl, which a partial
# mirror upgrade can leave ABI-broken. A stale libexpat also breaks pip.
apt update -y
apt install -y "${APT_OPTS[@]}" python pulseaudio tur-repo libexpat rsync openssh termux-api clang
apt install -y "${APT_OPTS[@]}" python-onnxruntime python-numpy python-scipy python-pillow

echo "== python deps"
cd "$REPO_DIR"
# Termux forbids upgrading pip; the `python` package provides it.

req="$(mktemp)"
trap 'rm -f "$req"' EXIT
grep -v '^openwakeword' requirements.txt > "$req"
if ! pip install -r "$req"; then
  echo "pip install failed; retrying without webrtcvad-wheels" \
       "(opencompanion falls back to the built-in energy VAD)"
  grep -v '^webrtcvad-wheels' "$req" > "$req.novad"
  mv "$req.novad" "$req"
  pip install -r "$req"
fi

echo "== openwakeword"
# --no-deps: the full install pulls in scikit-learn and a CMake build.
pip install --no-deps openwakeword

SITE_PACKAGES="$(python -c 'import site; print(site.getsitepackages()[0])')"
if ! python -c "import sklearn" 2>/dev/null; then
  # openwakeword imports sklearn at module load but never uses it on the onnx path.
  echo "== stubbing sklearn"
  mkdir -p "$SITE_PACKAGES/sklearn"
  cat > "$SITE_PACKAGES/sklearn/__init__.py" <<'EOF'
"""Stub: openwakeword imports sklearn at module load but opencompanion never uses it."""
EOF
  cat > "$SITE_PACKAGES/sklearn/linear_model.py" <<'EOF'
class LogisticRegression:
    def __init__(self, *args, **kwargs):
        raise NotImplementedError("sklearn stub: install real scikit-learn to train custom verifiers")
EOF
  cat > "$SITE_PACKAGES/sklearn/pipeline.py" <<'EOF'
class Pipeline:
    def __init__(self, *args, **kwargs):
        raise NotImplementedError("sklearn stub: install real scikit-learn to train custom verifiers")
EOF
  cat > "$SITE_PACKAGES/sklearn/preprocessing.py" <<'EOF'
class StandardScaler:
    def __init__(self, *args, **kwargs):
        raise NotImplementedError("sklearn stub: install real scikit-learn to train custom verifiers")
EOF
fi

WAKEWORD_MODEL="hey_jarvis"
if [ -f config.yaml ]; then
  WAKEWORD_MODEL="$(python -c "
import yaml
cfg = yaml.safe_load(open('config.yaml')) or {}
print((cfg.get('wakeword') or {}).get('model') or '')
" 2>/dev/null || true)"
fi
[ -n "$WAKEWORD_MODEL" ] || WAKEWORD_MODEL="hey_jarvis"
OC_WAKEWORD_MODEL="$WAKEWORD_MODEL" python -c "
import os
import openwakeword.utils as u
u.download_models([os.environ['OC_WAKEWORD_MODEL']])
"

# termux-am can hang on recent Android; /system/bin/am works.
echo "== am shim"
AM="$PREFIX/bin/am"
if ! grep -q '/system/bin/am' "$AM" 2>/dev/null; then
  cp "$AM" "$AM.termux-orig"
  cat > "$AM" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec /system/bin/am "$@"
EOF
  chmod +x "$AM"
  echo "shimmed $AM (backup at $AM.termux-orig)"
else
  echo "$AM already shimmed"
fi

echo "== root grants"
BROWSER_PACKAGE=""
if [ -f config.yaml ]; then
  BROWSER_PACKAGE="$(python -c "
import yaml
cfg = yaml.safe_load(open('config.yaml')) or {}
print((cfg.get('face') or {}).get('browser_package') or '')
" 2>/dev/null || true)"
fi
# Interpolated into a root shell below, so restrict it to package-name characters.
case "$BROWSER_PACKAGE" in
  *[!A-Za-z0-9_.]*)
    echo "ERROR: face.browser_package in config.yaml must match ^[A-Za-z0-9_.]+\$;" \
         "got '$BROWSER_PACKAGE'" >&2
    exit 1
    ;;
esac
if command -v su >/dev/null 2>&1; then
  for pkg in com.termux com.termux.api; do
    su -c "pm grant $pkg android.permission.RECORD_AUDIO" || true
    su -c "pm grant $pkg android.permission.CAMERA" || true
    su -c "pm grant $pkg android.permission.POST_NOTIFICATIONS" || true
  done
  # Android revokes the mic when Termux is backgrounded unless this is set.
  su -c "appops set com.termux RECORD_AUDIO allow" || true
  [ -n "$BROWSER_PACKAGE" ] && { su -c "pm enable '$BROWSER_PACKAGE'" || true; }
  su -c "settings put secure immersive_mode_confirmations confirmed" || true
else
  echo "WARNING: su not available; skipping root grants (RECORD_AUDIO/CAMERA/POST_NOTIFICATIONS," \
       "appops, pm enable $BROWSER_PACKAGE, immersive_mode_confirmations). Grant these by hand."
fi

echo "== companion apps"
for pkg_spec in "com.termux.api:Termux:API" "org.kde.kdeconnect_tp:KDE Connect"; do
  pkg="${pkg_spec%%:*}"
  label="${pkg_spec#*:}"
  if ! pm list packages 2>/dev/null | grep -q "package:$pkg$"; then
    echo "MISSING: install $label ($pkg) from F-Droid"
  fi
done

echo "== config"
[ -f config.yaml ] || echo "NOTE: copy config.example.yaml to config.yaml and edit it"
[ -f .env ] || echo "NOTE: copy .env.example to .env and fill in secrets"
mkdir -p logs memory/journal

echo "install-phone.sh done"
