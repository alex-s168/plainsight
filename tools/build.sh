#!/bin/sh
# Runs a Gradle build in the dev shell and returns as soon as Gradle reports the result. The dev shell
# wrapper can outlive the build while a Gradle daemon keeps running, so it is started detached (own
# session, no inherited file descriptors) and not waited for.
#   tools/build.sh [tasks...]   default: build
exec python3 - "$(cd "$(dirname "$0")/.." && pwd)" "$@" <<'PY'
import os, re, subprocess, sys, time
root, tasks = sys.argv[1], sys.argv[2:] or ["build"]
log = os.path.join(root, "build", "gradle.log")
os.makedirs(os.path.dirname(log), exist_ok=True)
with open(log, "w") as out:
    subprocess.Popen([os.path.join(root, "dev"), "./gradlew", "--no-configuration-cache", "--console=plain", *tasks],
                     cwd=root, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                     close_fds=True, start_new_session=True)
deadline = time.time() + 900
text = ""
while time.time() < deadline:
    text = open(log).read()
    if re.search(r"^BUILD (SUCCESSFUL|FAILED)", text, re.M):
        break
    time.sleep(0.5)
for line in text.splitlines():
    if re.search(r"error:|warning: \[|FAILED|^BUILD", line):
        print(line)
sys.exit(0 if re.search(r"^BUILD SUCCESSFUL", text, re.M) else 1)
PY
