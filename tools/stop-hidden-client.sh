#!/bin/sh
# Stops a hidden dev client and its headless compositor:  tools/stop-hidden-client.sh [main|axiom]
# "main" is this checkout's hidden test client (its slot, see tools/instance.py); other checkouts' clients are
# left alone.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-main}" in
	axiom) DIR=run-axiom; NAME=axiom ;;
	*) DIR=run-hidden; eval "$(cd "$ROOT" && python3 tools/instance.py env)" ;;
esac
# the game JVM runs with its working directory in the run directory (a worktree's run-hidden may be a symlink
# to the main checkout's, so also require this checkout's Loom launch config on the command line)
RUNDIR="$(readlink -f "$ROOT/$DIR")"
for pid in $(pgrep -f "Dfabric.dli.env=client"); do
	[ "$(readlink -f /proc/$pid/cwd)" = "$RUNDIR" ] || continue
	tr '\0' '\n' < /proc/$pid/cmdline | grep -qxF -- "-Dfabric.dli.config=$ROOT/.gradle/loom-cache/launch.cfg" && kill "$pid"
done
sleep 3
pkill -f "plainsight-hidden-$NAME/sway.conf"
exit 0
