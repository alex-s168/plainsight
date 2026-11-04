# Notes for agents

## Environment: always use the Nix dev shell

The host is musl-based, so the host JDKs (`/usr/lib/jvm/*`) and Gradle's native libraries do not work
(`libgradle-fileevents.so: strtoll_l: symbol not found`, "Could not initialize native services").
Run every build, test and client command through `./dev`, which runs it inside `nix develop` (flake.nix:
JetBrains Runtime 25, Mesa, headless sway, ImageMagick, Python) with a clean `LD_LIBRARY_PATH`:

```sh
./dev ./gradlew build             # jar + unit tests
./dev ./gradlew compileJava
./dev ./gradlew test
```

`tools/hidden-client.sh` (and so `tools/mc.py start`) re-executes itself through `./dev` when not
already in the shell. See README.md, "Development", for the full tool list.

## Testing

* Test only in the hidden client (`tools/mc.py start [gl]`, run-hidden/, bridge :25612), never in the
  user's visible game. Voxy needs OpenGL: `tools/mc.py start gl`.
* End-to-end tests: `tools/ingame_test.py [filter...]`. Jars added to run-hidden/mods for a test are
  removed afterwards (the hidden client normally runs without extra mods).
* The suite builds its scenery in the `plainsight-test` world and opens it itself when no world is
  loaded: start the client and run the tests, without `tools/mc.py world` (which opens `plainsight-dev`,
  where the cave and rock tests fail).
* In a worktree, `run-hidden` is a symlink to the main checkout's (`ln -s ~/Repos/plainsight/run-hidden`).
* Hot-swap (`tools/mc.py hotswap`) cannot add fields or change mixins: restart the client instead.

## Installing into the user's Prism instance

`~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/main/minecraft/mods`. The game
may be running: never overwrite the jar in place. Copy to a temporary file in that folder and `mv` it
over the old jar (atomic rename).

## Shell quirks

`sed`/`cat` are BSD-like (no `cat -A`, `head -0` fails); use Python for non-trivial edits.
Unzip wildcard extraction is unreliable; use Python's zipfile.

## Issues and commits

Work is tracked as GitHub issues in alex-s168/plainsight (`gh issue list`, `gh issue view N`).

* A new request from the user: create an issue for each item first (`gh issue create`), then work on
  them one at a time. Items the user wants recorded but not done yet get the `later` label.
* One commit per issue where practical, referencing it (`Fixes #N` closes it when pushed to main).
* Before closing an issue, test the change (unit tests and, for anything in game, the hidden client).
* Push to `origin main` (https://github.com/alex-s168/plainsight) once the work is on main.
