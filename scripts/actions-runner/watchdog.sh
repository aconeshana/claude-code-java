#!/bin/bash
# actions-runner stale-connection watchdog: detect a silently dead long-poll
# connection and kill the listener so launchd KeepAlive restarts a fresh
# session.
#
# Background: the runner's broker long-poll connection
# (broker.actions.githubusercontent.com) can go silently dead — the process
# stays alive and GitHub still shows it online, but it never receives job
# dispatches, so jobs sit queued forever. Killing and restarting the process
# picks up a dispatched job within seconds.
#
# Liveness signal is the broker socket, NOT the log mtime. A healthy idle
# runner holds exactly one ESTABLISHED connection to GitHub:443 and writes
# nothing to _diag for hours on end — a single healthy session spanning
# 2026-08-31 → 09-09 is on record. An earlier revision keyed off log
# staleness alone and killed a healthy runner every ~50 minutes, which is
# exactly the restart pattern the _diag logs show.
#
# Heuristic: kill only when BOTH signals agree — the listener holds no
# ESTABLISHED connection to :443 AND the newest log has been idle for
# $RUNNER_WATCHDOG_STALE_MIN minutes. The socket test alone clears an
# idle-but-healthy runner immediately; the log test is the backstop for a
# connection that is gone while the retry/backoff loop masks its absence.
#
# Environment (RUNNER_DIR etc.) comes from .env next to this script
# (gitignored, machine-specific paths); see .env.example for the template.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/.env"
fi

RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner}"
STALE_MIN="${RUNNER_WATCHDOG_STALE_MIN:-60}"

# No listener at all is launchd's (KeepAlive) business, not ours.
pid=$(pgrep -f "Runner.Listener" | head -1)
if [ -z "$pid" ]; then
  exit 0
fi

# A live broker long-poll means healthy, however quiet the log is.
if lsof -nP -a -p "$pid" -iTCP -sTCP:ESTABLISHED 2>/dev/null | grep -qE -- '->[^ ]*:443 '; then
  exit 0
fi

latest_log=$(ls -t "$RUNNER_DIR/_diag"/Runner_*.log 2>/dev/null | head -1)
if [ -z "$latest_log" ]; then
  exit 0
fi

now=$(date +%s)
mtime=$(stat -f %m "$latest_log")
age_min=$(( (now - mtime) / 60 ))

if [ "$age_min" -lt "$STALE_MIN" ]; then
  exit 0
fi

echo "$(date -u +%FT%TZ) watchdog: pid $pid has no :443 connection and log idle ${age_min}min (>$STALE_MIN), killing runner" \
  >> "$RUNNER_DIR/_diag/watchdog.log"
kill "$pid" 2>/dev/null
exit 0
