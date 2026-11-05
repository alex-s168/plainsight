#!/bin/sh
# Stops a hidden dev client and its headless compositor:  tools/stop-hidden-client.sh [main|axiom]
NAME="${1:-main}"
case "$NAME" in axiom) DIR=run-axiom ;; *) DIR=run-hidden ;; esac
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# the game JVM runs with its working directory in the run directory
for pid in $(pgrep -f "Dfabric.dli.env=client"); do
	# (resolved: in a worktree the run directory is a symlink to the main checkout's)
	[ "$(readlink /proc/$pid/cwd)" = "$(readlink -f "$ROOT/$DIR")" ] && kill "$pid"
done
sleep 3
pkill -f "plainsight-hidden-$NAME/sway.conf"
exit 0
