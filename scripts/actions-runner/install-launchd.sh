#!/bin/bash
# Install the actions-runner and its watchdog as macOS launchd agents.
#
# Produces two agents:
#   1. <label-runner>  — runs the runner's run.sh directly; KeepAlive=true
#      (auto-restart within seconds after exit/crash/watchdog kill),
#      RunAtLoad=true (starts at login)
#   2. <label-watchdog> — runs watchdog.sh every 10 minutes to detect a
#      dead long-poll connection (see watchdog.sh header for the details)
#
# Usage:
#   ./install-launchd.sh              # install and start (idempotent)
#   ./install-launchd.sh --uninstall
#
# Environment comes from .env next to this script (template .env.example,
# gitignored).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/.env"
fi

RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner}"
LABEL_RUNNER="${RUNNER_LAUNCHD_LABEL_RUNNER:-dev.local.actions-runner}"
LABEL_WATCHDOG="${RUNNER_LAUNCHD_LABEL_WATCHDOG:-dev.local.actions-runner-watchdog}"
LAUNCHAGENTS_DIR="${RUNNER_LAUNCHAGENTS_DIR:-$HOME/Library/LaunchAgents}"
UID_N=$(id -u)

if [ ! -f "$RUNNER_DIR/.runner" ]; then
  echo "error: $RUNNER_DIR/.runner not found — register the runner first" >&2
  exit 1
fi

# Bootout only — never delete the plist here. The install path bootstraps the
# plist it just wrote, so an rm in this step makes bootstrap read a missing
# file ("Bootstrap failed: 5: Input/output error").
bootout() {
  launchctl bootout "gui/$UID_N/$1" 2>/dev/null || true
}

uninstall() {
  local label="$1"
  bootout "$label"
  rm -f "$LAUNCHAGENTS_DIR/$label.plist"
}

if [ "${1:-}" = "--uninstall" ]; then
  uninstall "$LABEL_RUNNER"
  uninstall "$LABEL_WATCHDOG"
  echo "uninstalled: $LABEL_RUNNER, $LABEL_WATCHDOG"
  exit 0
fi

# Retire the previous agents BEFORE killing processes: while a KeepAlive agent
# is still loaded, launchd resurrects whatever we kill.
bootout "$LABEL_RUNNER"
bootout "$LABEL_WATCHDOG"

# Kill any leftover nohup/manual runner: two live listeners compete for job
# dispatches, and the registration only tracks the last starter.
pkill -f "Runner.Listener" 2>/dev/null || true
pkill -f "$RUNNER_DIR/run.sh" 2>/dev/null || true
sleep 2

# ---- agent 1: the runner itself ----
mkdir -p "$LAUNCHAGENTS_DIR"
cat > "$LAUNCHAGENTS_DIR/$LABEL_RUNNER.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL_RUNNER</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>$RUNNER_DIR/run.sh</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$RUNNER_DIR</string>
    <key>KeepAlive</key>
    <true/>
    <key>RunAtLoad</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$RUNNER_DIR/launchd.out.log</string>
    <key>StandardErrorPath</key>
    <string>$RUNNER_DIR/launchd.err.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HOME</key>
        <string>$HOME</string>
        <key>PATH</key>
        <string>/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin</string>
    </dict>
</dict>
</plist>
EOF

# ---- agent 2: the watchdog (every 10 minutes) ----
cat > "$LAUNCHAGENTS_DIR/$LABEL_WATCHDOG.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL_WATCHDOG</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>$SCRIPT_DIR/watchdog.sh</string>
    </array>
    <key>StartInterval</key>
    <integer>600</integer>
    <key>RunAtLoad</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$RUNNER_DIR/watchdog-launchd.log</string>
    <key>StandardErrorPath</key>
    <string>$RUNNER_DIR/watchdog-launchd.log</string>
</dict>
</plist>
EOF

chmod +x "$SCRIPT_DIR/watchdog.sh"

# Idempotent: drop any still-loaded instance, leaving the plists we just wrote
# in place, then bootstrap fresh.
bootout "$LABEL_RUNNER"
bootout "$LABEL_WATCHDOG"
launchctl bootstrap "gui/$UID_N" "$LAUNCHAGENTS_DIR/$LABEL_RUNNER.plist"
launchctl bootstrap "gui/$UID_N" "$LAUNCHAGENTS_DIR/$LABEL_WATCHDOG.plist"

echo "installed:"
echo "  $LABEL_RUNNER  -> $RUNNER_DIR/run.sh (KeepAlive + RunAtLoad)"
echo "  $LABEL_WATCHDOG -> $SCRIPT_DIR/watchdog.sh (every 600s)"
echo "verify: launchctl print gui/$UID_N/$LABEL_RUNNER | grep state"
