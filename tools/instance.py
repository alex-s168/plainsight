#!/usr/bin/env python3
"""Hidden-client slots, so several checkouts (worktrees) can each run their own hidden test client.

    tools/instance.py                 show this checkout's slot (ports, run dir, compositor state)
    tools/instance.py setup [N]       give this checkout its own slot (the lowest free one, or N) and its own
                                      run-hidden/ (replaces the run-hidden symlink, seeded from the main checkout)
    tools/instance.py release         back to slot 0 (removes .plainsight-instance; run-hidden/ is kept)
    tools/instance.py list            slots of all worktrees of this repository
    tools/instance.py env             shell assignments for tools/hidden-client.sh and stop-hidden-client.sh

The slot comes from $PLAINSIGHT_INSTANCE, else from the untracked file .plainsight-instance in the checkout,
else 0. Slot 0 is the classic setup (bridge 25612, JDWP 5011, compositor $XDG_RUNTIME_DIR/plainsight-hidden-main).
Slot N >= 1 uses bridge 25620+N, JDWP 5020+N and plainsight-hidden-sN. Every checkout already has its own
Gradle project dir, so its client runs in its own <checkout>/run-hidden.
"""
import os
import socket
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILE = ".plainsight-instance"
MAX_SLOT = 19


class Slot:
    def __init__(self, n):
        self.n = n
        self.name = "main" if n == 0 else "s%d" % n
        self.bridge = 25612 if n == 0 else 25620 + n
        self.jdwp = 5011 if n == 0 else 5020 + n

    def state_dir(self):
        return os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "plainsight-hidden-" + self.name)


def read_slot(root):
    try:
        with open(os.path.join(root, FILE)) as f:
            return int(f.read().strip() or 0)
    except (OSError, ValueError):
        return 0


def current(root=ROOT):
    env = os.environ.get("PLAINSIGHT_INSTANCE", "").strip()
    return Slot(int(env) if env.isdigit() else read_slot(root))


def worktrees():
    out = subprocess.run(["git", "-C", ROOT, "worktree", "list", "--porcelain"], capture_output=True, text=True).stdout
    return [l[len("worktree "):] for l in out.splitlines() if l.startswith("worktree ")]


def main_checkout():
    wts = worktrees()
    return wts[0] if wts else ROOT


def port_free(port):
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def setup(want=None):
    me = os.path.realpath(ROOT)
    if me == os.path.realpath(main_checkout()):
        sys.exit("this is the main checkout: it keeps slot 0")
    taken = {read_slot(w): w for w in worktrees() if os.path.realpath(w) != me and read_slot(w)}
    if want is None:
        want = read_slot(ROOT) or next((n for n in range(1, MAX_SLOT + 1) if n not in taken
                                        and port_free(Slot(n).bridge) and port_free(Slot(n).jdwp)), None)
        if want is None:
            sys.exit("no free slot")
    elif want in taken:
        sys.exit("slot %d is taken by %s" % (want, taken[want]))
    with open(os.path.join(ROOT, FILE), "w") as f:
        f.write("%d\n" % want)
    own_run_dir()
    show()


def own_run_dir():
    """Replaces a run-hidden symlink by a directory of this checkout, seeded with the main checkout's options and
    config (not its worlds, guess caches or extra mods: the test suite builds its world itself)."""
    run = os.path.join(ROOT, "run-hidden")
    if os.path.islink(run):
        os.unlink(run)
    if os.path.isdir(run):
        return
    os.makedirs(os.path.join(run, "mods"))
    src = os.path.join(main_checkout(), "run-hidden")
    if os.path.isfile(os.path.join(src, "options.txt")):
        subprocess.run(["cp", os.path.join(src, "options.txt"), run], check=True)
    if os.path.isdir(os.path.join(src, "config")):
        subprocess.run(["cp", "-R", os.path.join(src, "config"), run], check=True)


def show():
    s = current()
    run = os.path.join(ROOT, "run-hidden")
    kind = "symlink to " + os.path.realpath(run) if os.path.islink(run) else ("own directory" if os.path.isdir(run) else "missing")
    print("slot %d (%s): bridge :%d, JDWP :%d, compositor %s, run-hidden: %s"
          % (s.n, s.name, s.bridge, s.jdwp, s.state_dir(), kind))
    if s.n != 0 and os.path.islink(run):
        print("warning: run-hidden is still shared; run tools/instance.py setup")


def list_all():
    for w in worktrees():
        s = Slot(read_slot(w))
        up = "running" if not port_free(s.bridge) else "-"
        print("%-3d %-7s bridge :%-5d %-8s %s" % (s.n, s.name, s.bridge, up, w))


def main(argv):
    op = argv[0] if argv else "show"
    if op == "setup":
        setup(int(argv[1]) if len(argv) > 1 else None)
    elif op == "release":
        try:
            os.unlink(os.path.join(ROOT, FILE))
        except FileNotFoundError:
            pass
        show()
    elif op == "list":
        list_all()
    elif op == "env":
        s = current()
        print("SLOT=%d NAME=%s PORT=%d JDWP=%d" % (s.n, s.name, s.bridge, s.jdwp))
    elif op == "show":
        show()
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
