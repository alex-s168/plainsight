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

## Layout

Two mods: plainsight (`src/`) and InfiniTerra (`infiniterra/`, Gradle subproject, nested in plainsight's
jar). InfiniTerra owns terrain rendering and LOD mod interop (guessed terrain, worldgen servers, camera
passes, detached camera, the Voxy/Sodium/Iris mixins for those); plainsight only uses
`dev.infiniterra.api`, never `dev.infiniterra.impl` or its mixins. Design and plans: issue #48.
`./dev ./gradlew build` builds and tests both.

## Testing

* Test only in the hidden client (`tools/mc.py start [gl]`, run-hidden/, bridge :25612), never in the
  user's visible game. Voxy needs OpenGL: `tools/mc.py start gl`.
* End-to-end tests: `tools/ingame_test.py [filter...]`. Jars added to run-hidden/mods for a test are
  removed afterwards (the hidden client normally runs without extra mods).
* The suite builds its scenery in the `plainsight-test` world and opens it itself when no world is
  loaded: start the client and run the tests, without `tools/mc.py world` (which opens `plainsight-dev`,
  where the cave and rock tests fail).
* In a worktree, `run-hidden` is a symlink to the main checkout's (`ln -s ~/Repos/plainsight/run-hidden`),
  unless the worktree has its own slot (next section).
* Hot-swap (`tools/mc.py hotswap`) cannot add fields or change mixins: restart the client instead.

## Parallel worktrees: one hidden client each

All checkouts share slot 0 by default: bridge :25612, JDWP :5011, the compositor state in
`$XDG_RUNTIME_DIR/plainsight-hidden-main` and (through the symlink) the main checkout's `run-hidden/`. Two clients
on slot 0 cannot both start, and the tools of one worktree drive and stop the other's client. When other
worktrees may run a hidden client at the same time (several agents in parallel), give yours its own slot
first:

```sh
tools/instance.py setup     # lowest free slot N >= 1; writes .plainsight-instance (untracked)
tools/instance.py           # this checkout's slot, ports and run dir
tools/instance.py list      # slots of all worktrees, and which bridges are up
```

* Slot N uses bridge :2562N (25620+N), JDWP :502N (5020+N) and `plainsight-hidden-sN`. `tools/mc.py`,
  `tools/ingame_test.py`, `tools/hidden-client.sh`, `tools/stop-hidden-client.sh` and `tools/mc.py hotswap`
  all follow it; `PLAINSIGHT_INSTANCE=N` overrides the file.
* `setup` replaces the `run-hidden` symlink with the worktree's own directory, seeded with the main
  checkout's `options.txt` and `config/` (no worlds, guess caches or extra mods: the suite builds its
  `plainsight-test` world itself on first run, which takes a few minutes). Mods a test adds stay in this
  worktree.
* `tools/mc.py stop` only stops this checkout's client. Never `pkill` Java or sway broadly: other
  worktrees' clients are running.
* The main checkout always stays on slot 0. The Axiom study client (`--axiom`, :25611) is not slotted:
  only one at a time.
* Each client takes about 5 GB of RAM and a share of the GPU; keep to about four at once.
* `tools/instance.py release` returns the worktree to slot 0 (its run-hidden/ directory is kept; re-create
  the symlink by hand if wanted).

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
