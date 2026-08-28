#!/usr/bin/env bash
# Push the repo to the phone, or pull memory/logs back for backup.
#   OC_HOST=phone ./scripts/sync.sh          # push
#   OC_HOST=phone ./scripts/sync.sh --pull   # pull memory/ and logs/ into ./phone-data/
set -euo pipefail
: "${OC_HOST:?set OC_HOST to the phone ssh destination (e.g. an ~/.ssh/config alias)}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="$OC_HOST:opencompanion/"

ARG="${1:-}"
case "$ARG" in
  ""|--pull) ;;
  *)
    echo "usage: $0 [--pull]" >&2
    exit 1
    ;;
esac

if [ "$ARG" = "--pull" ]; then
  mkdir -p "$HERE/phone-data"
  # Either directory may not exist yet on a fresh phone.
  rsync -av "$REMOTE"memory/ "$HERE/phone-data/memory/" || true
  rsync -av "$REMOTE"logs/ "$HERE/phone-data/logs/" || true
  exit 0
fi

rsync -av --delete \
  --exclude .git \
  --exclude .venv \
  --exclude docs/ \
  --exclude __pycache__ \
  --exclude .pytest_cache \
  --exclude config.yaml \
  --exclude .env \
  --exclude memory/facts.md \
  --exclude memory/journal/ \
  --exclude logs/ \
  --exclude phone-data/ \
  "$HERE/" "$REMOTE"
echo "synced to $REMOTE"
