#!/usr/bin/env python3
"""Control a hidden dev client through its dev bridge.

CLI:   tools/mc.py [--axiom] OP ARGS...
  status | cmd "time set day" | chat [since] | shot out.png | full out.png | world [name]
  key SCANCODE [tap|press|release] [mods] | type TEXT | move X Y | click X Y [button] | drag X1 Y1 X2 Y2 [button]
  scroll DY [X Y] | look YAW PITCH | tp X Y Z | static CLASS METHOD | state | hotswap | start | stop | wait
  uiclick/uidrag/uitype/uikey/uiscroll  feed Axiom's ImGui directly (only with --axiom)

Keys are SDL scancodes (MC 26.3 uses SDL): W=26 A=4 S=22 D=7 Q=20 E=8 Space=44 LShift=225 LCtrl=224
LAlt=226 RShift=229 Escape=41 Tab=43 Enter=40. Mouse buttons: 1 left, 2 middle, 3 right.

Targets: the hidden test client (default; run-hidden/, bridge 25612, JDWP 5011), `--axiom` the vanilla + Axiom
client (run-axiom/, bridge 25611) used to study Axiom's UI, `--visible` the visible runClient (run/, 25610).
Importable: `import mc; c = mc.Client(); c.key(26, "press")`.
"""
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SC = dict(W=26, A=4, S=22, D=7, Q=20, E=8, R=21, F=9, G=10, X=27, Y=28, Z=29, C=6, V=25, SPACE=44, LSHIFT=225,
          LCTRL=224, LALT=226, RSHIFT=229, ESC=41, TAB=43, ENTER=40, DELETE=76, BACKSPACE=42, N=17, M=16, MINUS=45, EQUALS=46)
MOD_SHIFT, MOD_CTRL, MOD_ALT = 0x0001, 0x0040, 0x0100  # SDL_KMOD_LSHIFT / LCTRL / LALT


class Client:
    def __init__(self, target="main"):
        self.target = target
        self.port = {"axiom": 25611, "visible": 25610}.get(target, 25612)
        self.base = "http://127.0.0.1:%d" % self.port
        self.run_dir = os.path.join(ROOT, {"axiom": "run-axiom", "visible": "run"}.get(target, "run-hidden"))

    # -- transport -------------------------------------------------------------------------------------
    def call(self, endpoint, http_method="POST", **params):
        url = self.base + endpoint + ("?" + urllib.parse.urlencode(params) if params else "")
        req = urllib.request.Request(url, method=http_method)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read().decode()
        except urllib.error.HTTPError as e:
            return "error %d: %s" % (e.code, e.read().decode())
        except OSError as e:
            return "bridge not reachable: %s" % e

    # -- game ------------------------------------------------------------------------------------------
    def status(self):
        return self.call("/status", "GET")

    def command(self, c, wait=0.7):
        """Runs a command and returns the chat lines it produced."""
        before = int(self.call("/chatlog", "GET", since=10**9).strip().split("next=")[-1] or 0)
        res = self.call("/command", c=c)
        time.sleep(wait)
        lines = self.call("/chatlog", "GET", since=before)
        return res + "\n" + "\n".join(l for l in lines.splitlines() if not l.startswith("next="))

    def world(self, name="plainsight-dev", mode="creative", preset="flat"):
        return self.call("/world", name=name, mode=mode, preset=preset)

    def static(self, cls, method, *args):
        return self.call("/static", **{"class": cls, "method": method, "args": ",".join(str(a) for a in args)})

    def ed(self, method, *args):
        """Calls dev.plainsight.editor.EditorDebug.<method>(args...)."""
        return self.static("dev.plainsight.editor.EditorDebug", method, *args)

    def state(self):
        """Plainsight's editor state dump (see dev.plainsight.editor.EditorDebug)."""
        return self.static("dev.plainsight.editor.EditorDebug", "dump")

    # -- input through Minecraft's handlers (what plainsight's ImGui and camera see) -------------------
    def key(self, sc, action="tap", mods=0):
        return self.call("/key", k=sc, action=action, mods=mods)

    def type(self, text):
        return self.call("/char", t=text)

    def move(self, x, y):
        return self.call("/mouse", x=x, y=y)

    def button(self, b=1, action="tap"):
        return self.call("/click", button=b, action=action)

    def click(self, x, y, b=1, settle=0.08):
        self.move(x, y)
        time.sleep(settle)
        self.button(b, "press")
        time.sleep(settle)
        return self.button(b, "release")

    def drag(self, x1, y1, x2, y2, b=1, steps=12, dt=0.03):
        self.move(x1, y1)
        time.sleep(0.08)
        self.button(b, "press")
        time.sleep(0.08)
        for i in range(1, steps + 1):
            t = i / steps
            self.move(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
            time.sleep(dt)
        time.sleep(0.08)
        return self.button(b, "release")

    def scroll(self, dy, x=None, y=None):
        if x is not None:
            self.move(x, y)
            time.sleep(0.05)
        return self.call("/scroll", dy=dy)

    def hold(self, sc, seconds, mods=0):
        self.key(sc, "press", mods)
        time.sleep(seconds)
        return self.key(sc, "release", mods)

    # -- screenshots -----------------------------------------------------------------------------------
    def shot(self, path):
        return self.call("/screenshot", "GET", path=os.path.abspath(path))

    def fullshot(self, path):
        """Whole compositor output (game + ImGui), taken with grim."""
        rt = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/user/%d" % os.getuid()),
                          "plainsight-hidden-" + self.target)
        if not os.path.isdir(rt):
            return "hidden compositor not running"
        sockets = [s for s in os.listdir(rt) if s.startswith("wayland-") and not s.endswith(".lock")]
        if not sockets:
            return "hidden compositor not running"
        env = dict(os.environ, XDG_RUNTIME_DIR=rt, WAYLAND_DISPLAY=sockets[0])
        path = os.path.abspath(path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        r = subprocess.run([os.path.join(ROOT, "dev"), "grim", path], cwd=ROOT, env=env, capture_output=True, text=True)
        return path if r.returncode == 0 else r.stderr[-2000:]

    # -- Axiom's own ImGui (study client only) ---------------------------------------------------------
    def uiclick(self, x, y, b=0):
        return self.call("/imgui/click", x=x, y=y, button=b)

    # -- lifecycle -------------------------------------------------------------------------------------
    def start(self, timeout=400, gl=False):
        os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
        log = open(os.path.join(ROOT, "build", "hidden-launch-%s.log" % self.target), "w")
        env = dict(os.environ)
        if self.target == "axiom":
            env["TASK"] = "runAxiomClient"
        if gl:
            env["GL"] = "1"
        subprocess.Popen([os.path.join(ROOT, "tools", "hidden-client.sh")], cwd=ROOT, stdout=log,
                         stderr=subprocess.STDOUT, start_new_session=True, env=env)
        return self.wait(timeout)

    def wait(self, timeout=400):
        end = time.time() + timeout
        while time.time() < end:
            s = self.status()
            if not s.startswith("bridge not reachable"):
                return s
            time.sleep(1)
        return "timed out waiting for the client"

    def wait_world(self, timeout=120):
        end = time.time() + timeout
        while time.time() < end:
            s = self.status()
            if "player=" in s and "screen=none" in s:
                return s
            time.sleep(1)
        raise TimeoutError("world did not load: " + self.status())

    def stop(self):
        subprocess.run([os.path.join(ROOT, "tools", "stop-hidden-client.sh"), self.target])
        return "stopped"


def hotswap():
    # tools/build.sh does not wait for the dev shell wrapper, which can linger with a Gradle daemon
    build = subprocess.run([os.path.join(ROOT, "tools", "build.sh"), "classes", "devbridgeClasses"], cwd=ROOT,
                           capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if build.returncode != 0:
        return "build failed:\n" + build.stdout[-4000:] + build.stderr[-4000:]
    swap = subprocess.run([os.path.join(ROOT, "dev"), "java", "tools/Hotswap.java", "5011"], cwd=ROOT, capture_output=True,
                          text=True, stdin=subprocess.DEVNULL, timeout=120)
    return (swap.stdout + swap.stderr).strip()


def main(argv):
    target = "main"
    if argv and argv[0] in ("--axiom", "--visible"):
        target, argv = argv[0][2:], argv[1:]
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return
    c = Client(target)
    op, r = argv[0], argv[1:]
    f = lambda i, d=None: (r[i] if len(r) > i else d)
    out = {
        "status": lambda: c.status(),
        "cmd": lambda: c.command(" ".join(r)),
        "chat": lambda: c.call("/chatlog", "GET", since=f(0, 0)),
        "shot": lambda: c.shot(r[0]),
        "full": lambda: c.fullshot(r[0]),
        "world": lambda: c.world(f(0, "plainsight-dev"), f(1, "creative"), f(2, "flat")),
        "key": lambda: c.key(r[0], f(1, "tap"), f(2, 0)),
        "type": lambda: c.type(" ".join(r)),
        "move": lambda: c.move(r[0], r[1]),
        "click": lambda: c.click(float(r[0]), float(r[1]), int(f(2, 1))),
        "drag": lambda: c.drag(float(r[0]), float(r[1]), float(r[2]), float(r[3]), int(f(4, 1))),
        "scroll": lambda: c.scroll(r[0], f(1), f(2)),
        "look": lambda: c.call("/look", yaw=r[0], pitch=r[1]),
        "tp": lambda: c.call("/tp", x=r[0], y=r[1], z=r[2]),
        "static": lambda: c.static(r[0], r[1], *r[2:]),
        "ed": lambda: c.ed(r[0], *r[1:]),
        "state": lambda: c.state(),
        "uiclick": lambda: c.uiclick(r[0], r[1], f(2, 0)),
        "uidrag": lambda: c.call("/imgui/drag", x1=r[0], y1=r[1], x2=r[2], y2=r[3], button=f(4, 0)),
        "uitype": lambda: c.call("/imgui/type", t=" ".join(r)),
        "uikey": lambda: c.call("/imgui/key", k=r[0]),
        "uiscroll": lambda: c.call("/imgui/scroll", x=r[0], y=r[1], dy=f(2, -1)),
        "hotswap": hotswap,
        "start": lambda: c.start(gl=f(0) == "gl"),
        "stop": lambda: c.stop(),
        "wait": lambda: c.wait(),
    }.get(op)
    print(out() if out else "unknown operation " + op)


if __name__ == "__main__":
    main(sys.argv[1:])
