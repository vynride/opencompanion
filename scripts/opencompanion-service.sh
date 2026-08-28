#!/system/bin/sh
# Boot hook: start the companion as the Termux user if it is not already running.
# Called from a root boot hook (Magisk service.d, or cron). Idempotent; the
# Termux user and home are derived from the filesystem.
#
# `su <uid> -c "a; b &"` can run only the first command, so the startup body
# is written to a script file and run as a single `su -c "sh ..."`.
set -eu

TERMUX_HOME=/data/data/com.termux/files/home
PREFIX=/data/data/com.termux/files/usr
TERMUX_BIN="$PREFIX/bin"
TERMUX_UID=$(stat -c %u "$TERMUX_HOME")

if pgrep -f "opencompanion.main" >/dev/null 2>&1; then
  exit 0
fi

mkdir -p "$TERMUX_HOME/opencompanion/logs"

TERMUX_EXEC_PRELOAD=""
if [ -f "$PREFIX/lib/libtermux-exec.so" ]; then
  TERMUX_EXEC_PRELOAD="LD_PRELOAD=$PREFIX/lib/libtermux-exec.so"
fi

START_SCRIPT="$TERMUX_HOME/opencompanion/.opencompanion-service-start.sh"
cat > "$START_SCRIPT" <<STARTEOF
#!/data/data/com.termux/files/usr/bin/sh
cd "$TERMUX_HOME/opencompanion"
export PATH="$TERMUX_BIN:\$PATH"
export HOME="$TERMUX_HOME"
export PREFIX="$PREFIX"
# Android has no writable system-wide /tmp; without this Python temp files
# land in the working directory.
export TMPDIR="$PREFIX/tmp"
mkdir -p "\$TMPDIR"
${TERMUX_EXEC_PRELOAD:+export $TERMUX_EXEC_PRELOAD}
# A PulseAudio server surviving a reboot can be wedged; the daemon starts its own.
pulseaudio -k 2>/dev/null || true
sleep 1
nohup "$TERMUX_BIN/python" -m opencompanion.main >> "$TERMUX_HOME/opencompanion/logs/opencompanion.log" 2>&1 &
STARTEOF
chmod +x "$START_SCRIPT"

su "$TERMUX_UID" -c "sh $START_SCRIPT"
