#!/bin/sh
# Runs a Gradle build in the dev shell and returns as soon as Gradle reports the result. (The dev
# shell wrapper can outlive the build when a Gradle daemon keeps running; this does not wait for it.)
#   tools/build.sh [tasks...]   default: build
cd "$(dirname "$0")/.." || exit 1
LOG=build/gradle.log
mkdir -p build
: > "$LOG"
./dev ./gradlew --no-configuration-cache --console=plain "${@:-build}" > "$LOG" 2>&1 < /dev/null &
PID=$!
while kill -0 "$PID" 2>/dev/null; do
	grep -qE "^BUILD (SUCCESSFUL|FAILED)" "$LOG" && break
	sleep 0.5
done
grep -E "error:|warning: \[|FAILED|BUILD" "$LOG" | head -20
grep -q "^BUILD SUCCESSFUL" "$LOG"
