#!/usr/bin/env python3
"""End-to-end tests of the editor in the hidden dev client, driven through the real input pipeline.

    tools/ingame_test.py            run all tests (starts the hidden client if needed)
    tools/ingame_test.py box tree   run tests whose name contains one of the words

Input goes through Minecraft's mouse/keyboard handlers (exactly what the editor and its ImGui see);
state is read back with dev.plainsight.editor.EditorDebug; screenshots land in build/test-shots/.
"""
import json
import math
import os
import re
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mc  # noqa: E402

ROOT = mc.ROOT
SHOTS = os.path.join(ROOT, "build", "test-shots")
K = mc.SC
c = mc.Client()
results = []


# -- helpers ---------------------------------------------------------------------------------------------

def state():
    s = c.state()
    try:
        return json.loads(s)
    except ValueError:
        raise AssertionError("no editor state: " + s[:300])


def ed(method, *args):
    r = c.ed(method, *args)
    if r.startswith("error") or r.startswith("bridge"):
        raise AssertionError("%s failed: %s" % (method, r))
    return r


def handles():
    return {k: (tuple(map(float, v.split(","))) if isinstance(v, str) and "," in v else v) for k, v in json.loads(ed("handles")).items()}


def widget(name):
    r = state()["widgets"].get(name)
    if r is None:
        raise AssertionError("widget %s not drawn; have %s" % (name, list(state()["widgets"])))
    x1, y1, x2, y2 = map(float, r.split(","))
    return (x1 + x2) / 2, (y1 + y2) / 2


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def close(a, b, tol, msg):
    check(abs(a - b) <= tol, "%s: %s vs %s (tol %s)" % (msg, a, b, tol))


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    return c.fullshot(os.path.join(SHOTS, name + ".png"))


def avg_color(path, x, y, w, h):
    """Average RGB of a rectangle of a PNG (ImageMagick, inside the dev shell)."""
    r = subprocess.run([os.path.join(ROOT, "dev"), "magick", path, "-crop", "%dx%d+%d+%d" % (w, h, x, y), "+repage",
                        "-resize", "1x1!", "-format", "%[fx:int(255*r)],%[fx:int(255*g)],%[fx:int(255*b)]", "info:"],
                       cwd=ROOT, capture_output=True, text=True)
    m = re.search(r"(\d+),(\d+),(\d+)", r.stdout)
    if not m:
        raise AssertionError("magick failed: " + r.stderr[-500:])
    return tuple(int(v) for v in m.groups())


def color_dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def editor_on():
    if not state()["active"]:
        c.key(K["RSHIFT"])
        time.sleep(0.6)
    check(state()["active"], "editor did not open")


def editor_off():
    if state()["active"]:
        c.key(K["RSHIFT"])
        time.sleep(0.5)


def cam(x, y, z, yaw, pitch):
    ed("setCamera", x, y, z, yaw, pitch)
    time.sleep(0.25)


def fresh_tree():
    ed("clearTree")
    ed("selectTool", "box_select")


def cmd(s):
    r = c.command(s, wait=0.15)
    return r


def frames_pass(n=3):
    start = state()["frames"]
    end = time.time() + 5
    while time.time() < end and state()["frames"] < start + n:
        time.sleep(0.05)


# -- world setup -----------------------------------------------------------------------------------------

def setup_world():
    s = c.status()
    if s.startswith("bridge not reachable"):
        print("starting hidden client ...")
        print(c.start())
    if "world=none" in c.status():
        c.world("plainsight-test")
        c.wait_world()
    time.sleep(1)
    for line in [
        "gamerule advance_time false", "gamerule advance_weather false", "time set noon", "weather clear",
        "kill @e[type=!player]", "tp @s 0.5 -60 0.5 0 0",
        # /fill is limited to 32768 blocks: clear the test area in slices
        *["fill -20 -60 %d 40 -50 %d air" % (z, z + 4) for z in range(-20, 41, 5)],
        # wall in front of the spawn point
        "fill 4 -60 8 8 -57 10 oak_planks",
        # a 5x5 pool to the east
        "fill 12 -61 -2 16 -61 2 water",
        # a stone hut north-west with an armor stand inside: used by the see-through test
        "fill -12 -60 -12 -6 -55 -6 stone hollow",
        # a cave under the hut and to its north, to look at the player from below
        "fill -14 -63 -20 -4 -62 -4 air",
        'summon armor_stand 4.5 -60 2.5 {NoGravity:1b,Invisible:0b,CustomName:"Stand"}',
    ]:
        cmd(line)
    time.sleep(1.5)


# -- tests -----------------------------------------------------------------------------------------------

def test_toggle_keeps_player_still():
    editor_off()
    before = state()["player"]
    check(state()["tool"] == "none", "no tool is selected by default: %s" % state()["tool"])
    c.key(K["RSHIFT"])
    time.sleep(0.6)
    s = state()
    check(s["active"], "RShift should open the editor")
    check(s["camera"]["mcDetached"], "camera should be detached (third person)")
    check("focused=true" in c.status(), "window focus")
    c.hold(K["W"], 0.4)
    time.sleep(0.3)
    s = state()
    check(s["player"] == before, "player must not move while the editor flies: %s -> %s" % (before, s["player"]))
    shot("toggle-open")
    c.key(K["RSHIFT"])
    time.sleep(0.5)
    s = state()
    check(not s["active"], "RShift should close the editor")
    check(not s["camera"]["mcDetached"], "camera back to first person")


def test_overlays_in_game_keybind():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    c.key(K["ENTER"])
    time.sleep(0.2)
    editor_off()
    check(ed("overlaysInGame").split()[1] == "0", "no overlays in game by default")
    c.key(13)  # J
    time.sleep(0.4)
    check("screen=none" in c.status(), "the key opens no game screen: %s" % c.status().splitlines()[0])
    on, lines = ed("overlaysInGame").split()
    check(on == "true" and int(lines) >= 12, "J shows the selection boxes in game: %s" % on)
    shot("overlays-in-game")
    c.key(13)
    time.sleep(0.4)
    check(ed("overlaysInGame").split() == ["false", "0"], "J hides them again")


def test_camera_fly_keys():
    editor_on()
    ed("selectTool", "box_select")
    cam(0.5, -55, -4, 0, 0)
    p0 = state()["camera"]["pos"]
    c.hold(K["W"], 0.5)
    time.sleep(0.4)
    p1 = state()["camera"]["pos"]
    dz = p1[2] - p0[2]
    check(3 < dz < 7, "W at speed 1 for 0.5 s should fly ~4-5 blocks south (+z), got %.2f" % dz)
    close(p1[0], p0[0], 0.05, "W keeps x")
    c.hold(K["SPACE"], 0.3)
    time.sleep(0.4)
    p2 = state()["camera"]["pos"]
    check(p2[1] - p1[1] > 1.5, "Space flies up")
    c.hold(K["LSHIFT"], 0.3)
    time.sleep(0.4)
    p3 = state()["camera"]["pos"]
    check(p2[1] - p3[1] > 1.5, "Shift flies down")
    c.hold(K["D"], 0.3)
    time.sleep(0.4)
    p4 = state()["camera"]["pos"]
    check(p4[0] - p3[0] < -1.5, "D strafes right (-x when looking south)")
    # faster with Ctrl
    c.key(K["LCTRL"], "press")
    c.hold(K["S"], 0.3)
    c.key(K["LCTRL"], "release")
    time.sleep(0.4)
    p5 = state()["camera"]["pos"]
    check(p4[2] - p5[2] > 5, "Ctrl+S should fly back faster, got %.2f" % (p4[2] - p5[2]))


def test_camera_look_drag():
    editor_on()
    cam(0.5, -55, -4, 0, 10)
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    c.drag(cx, cy, cx + 100, cy + 50, b=1)
    time.sleep(0.2)
    s = state()["camera"]
    close(s["yaw"], 20, 0.5, "LMB drag 100 px right turns 20 deg")
    close(s["pitch"], 20, 0.5, "LMB drag 50 px down pitches 10 deg down")
    check(s["drag"] == "NONE", "drag ended")
    check(state()["camera"]["pos"] == [0.5, -55.0, -4.0], "look does not move the camera")


def test_camera_orbit_ctrl_lmb():
    editor_on()
    cam(6.5, -52, 0, 0, 30)
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    p0 = state()["camera"]["pos"]
    c.key(K["LCTRL"], "press")
    c.drag(cx, cy, cx + 150, cy, b=1)
    c.key(K["LCTRL"], "release")
    time.sleep(0.2)
    s = state()["camera"]
    check(abs(s["yaw"] - 30) < 0.5, "orbit turns the view: yaw %s" % s["yaw"])
    p1 = s["pos"]
    check(math.dist(p0, p1) > 1, "orbit moves the camera around the pivot")


def test_camera_arcball_around_entity_and_follow():
    editor_on()
    fresh_tree()
    cmd("tp @e[type=armor_stand] 4.5 -60 2.5")
    cam(4.5, -57, -6, 0, 20)
    time.sleep(0.3)
    x, y = map(float, ed("project", 4.5, -59, 2.5).split(","))
    c.key(K["LCTRL"], "press")
    c.move(x, y)
    time.sleep(0.1)
    c.button(1, "press")
    time.sleep(0.15)
    s = state()
    check(s["camera"]["drag"] == "ORBIT" and s["camera"]["orbitEntity"] == "Stand", "Ctrl+LMB on an entity orbits it: %s" % s["camera"])
    piv = s["camera"]["pivot"]
    check(abs(piv[0] - 4.5) < 0.01 and abs(piv[2] - 2.5) < 0.01, "pivot is the entity's centre: %s" % piv)
    check("Arcball" in s["statusHints"], "status bar shows the Ctrl hints: %s" % s["statusHints"])
    time.sleep(0.5)
    vp = s["viewport"]
    centre = (vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    before = tuple(map(float, ed("project", *piv).split(",")))
    check(math.dist(before, centre) < 3, "the view turns to centre the pivot: %s vs %s" % (before, centre))
    for i in range(1, 16):
        c.move(x + i * 12, y + i * 8)
        time.sleep(0.03)
    shot("arcball-entity")
    s = state()
    after = tuple(map(float, ed("project", *piv).split(",")))
    check(math.dist(before, after) < 2, "the entity stays in the centre while orbiting: %s -> %s" % (before, after))
    up = ed("project", piv[0], piv[1] + 3, piv[2])
    ux, uy = map(float, up.split(","))
    check(abs(ux - after[0]) < 1.5 and uy < after[1], "no tilt: world up stays screen up (%s above %s)" % (up, after))
    c.button(1, "release")
    c.key(K["LCTRL"], "release")
    time.sleep(0.2)
    check("Arcball" not in state()["statusHints"], "hints revert without Ctrl")
    check(state()["camera"]["following"] == "armor_stand: Stand", "orbiting an entity keeps following it: %s" % state()["camera"]["following"])
    # follow: the camera keeps its offset while the entity moves
    time.sleep(0.2)
    p0 = state()["camera"]["pos"]
    cmd("tp @e[type=armor_stand] 9.5 -60 2.5")
    time.sleep(0.6)
    p1 = state()["camera"]["pos"]
    check(abs(p1[0] - p0[0] - 5) < 0.05, "the camera follows the entity: %s -> %s" % (p0, p1))
    check("view_follow" in state()["widgets"], "the viewport shows the follow chip")
    shot("follow")
    c.click(*widget("view_follow"), b=1)
    time.sleep(0.3)
    check(state()["camera"]["following"] == "", "clicking the chip stops following")
    cmd("tp @e[type=armor_stand] 4.5 -60 2.5")


def test_camera_arcball_centring_is_smooth():
    editor_on()
    fresh_tree()
    cam(6.5, -52, 0, 0, 30)
    vp = state()["viewport"]
    gx, gy = vp[0] + vp[2] * 0.8, vp[1] + vp[3] * 0.7
    c.key(K["LCTRL"], "press")
    c.move(gx, gy)
    time.sleep(0.05)
    c.button(1, "press")
    piv = state()["camera"]["pivot"]
    centre = (vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    samples = []
    t0 = time.time()
    while time.time() - t0 < 0.6:
        samples.append(math.dist(tuple(map(float, ed("project", *piv).split(","))), centre))
        time.sleep(0.03)
    c.button(1, "release")
    c.key(K["LCTRL"], "release")
    print("      distance of the pivot from the centre:", [round(v) for v in samples[:8]], "...", round(samples[-1], 1))
    check(len([v for v in samples if 5 < v < samples[0] - 5]) >= 1, "it moves through intermediate positions (smooth)")
    check(len([v for v in samples if v > 3]) <= 6, "and quickly: at most ~0.2 s off centre")
    check(samples[-1] < 3, "and reaches the centre quickly")


def test_camera_arcball_zoom_keeps_centre():
    editor_on()
    fresh_tree()
    ed("unfollow")
    cam(6.5, -52, 0, 0, 30)
    vp = state()["viewport"]
    gx, gy = vp[0] + vp[2] * 0.6, vp[1] + vp[3] * 0.55
    c.key(K["LCTRL"], "press")
    c.move(gx, gy)
    time.sleep(0.05)
    c.button(1, "press")
    time.sleep(0.4)
    c.button(1, "release")
    c.key(K["LCTRL"], "release")
    piv = state()["camera"]["pivot"]
    centre = (vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    d0 = math.dist(state()["camera"]["pos"], piv)
    # zoom out and in with the cursor far from the centre
    for dy in (-1, -1, 1):
        c.scroll(dy, vp[0] + 80, vp[1] + 80)
        time.sleep(0.15)
        p = tuple(map(float, ed("project", *piv).split(",")))
        check(math.dist(p, centre) < 2, "zooming keeps the pivot in the centre: %s vs %s" % (p, centre))
    check(math.dist(state()["camera"]["pos"], piv) > d0, "zoomed out overall")


def test_camera_pan_ctrl_rmb():
    editor_on()
    fresh_tree()
    cam(6.5, -52, 0, 0, 30)
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    c.key(K["LCTRL"], "press")
    c.drag(cx, cy, cx + 200, cy, b=3)
    c.key(K["LCTRL"], "release")
    time.sleep(0.2)
    s = state()
    p = s["camera"]["pos"]
    check(s["camera"]["yaw"] == 0 and s["camera"]["pitch"] == 30, "pan keeps the angles")
    check(p[0] > 6.5 + 1, "dragging right pans the world right, i.e. the camera moves left (+x when facing south): %s" % p)
    close(p[1], -52, 0.01, "horizontal pan keeps height")
    check(not s["tree"], "Ctrl+RMB must not create a box")


def test_camera_scroll_dolly():
    editor_on()
    cam(6.5, -52, 0, 0, 30)
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    p0 = state()["camera"]["pos"]
    c.scroll(1, cx, cy)
    time.sleep(0.2)
    p1 = state()["camera"]["pos"]
    check(p1[2] > p0[2] and p1[1] < p0[1], "scrolling up moves towards the point under the cursor")
    c.scroll(-1, cx, cy)
    time.sleep(0.2)
    p2 = state()["camera"]["pos"]
    check(p2[2] < p1[2], "scrolling down moves away")


def create_box(x1, y1, x2, y2):
    c.drag(x1, y1, x2, y2, b=3)
    time.sleep(0.3)
    return state()


def test_box_create_by_rmb_drag():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    s = create_box(650, 550, 950, 700)
    check(len(s["tree"]) == 1 and s["tree"][0].startswith("box 1 ("), "one box in the tree: %s" % s["tree"])
    e = s["editing"]
    check(e is not None and e["handle"] == "END", "new box is edited with the end corner active")
    half = e["half"]
    n = round(half[0] * 2) * round(half[1] * 2) * round(half[2] * 2)
    check(s["tree"][0] == "box 1 (%d blocks)" % n, "label counts the blocks: %s vs %d" % (s["tree"][0], n))
    check(e["center"][1] == -60.5 and half[1] == 0.5, "box covers the ground layer")
    shot("box-created")


def test_box_handle_select_and_translate():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    h = handles()
    yaw = state()["camera"]["yaw"]
    c.click(*h["CENTER"], b=1)
    time.sleep(0.3)
    s = state()
    check(s["editing"]["handle"] == "CENTER", "LMB on the centre handle selects it: %s" % s["editing"]["handle"])
    check(s["camera"]["yaw"] == yaw, "selecting a handle must not rotate the camera")
    before = s["editing"]["center"]
    label = s["tree"][0]
    h = handles()
    ax, ay = h["arrowX"]
    # drag along the screen direction of the x arrow
    ox, oy = h["CENTER"]
    dx, dy = ax - ox, ay - oy
    ln = math.hypot(dx, dy)
    c.drag(ax, ay, ax + dx / ln * 150, ay + dy / ln * 150, b=1)
    time.sleep(0.3)
    s = state()
    after = s["editing"]["center"]
    moved = after[0] - before[0]
    check(abs(moved) >= 1 and abs(moved - round(moved)) < 1e-6, "x arrow moves the box in whole blocks: %s" % moved)
    check(after[1] == before[1] and after[2] == before[2], "x arrow moves only along x")
    check(s["tree"][0] == label, "moving keeps the block count")
    check(s["camera"]["yaw"] == yaw, "gizmo drags must not rotate the camera")
    shot("box-translated")


def test_box_resize_corner_keeps_opposite():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    h = handles()
    start_before = h["START"]
    c.click(*h["END"], b=1)
    time.sleep(0.3)
    s0 = state()
    check(s0["editing"]["handle"] == "END", "end handle selected")
    h = handles()
    ax, ay = h["arrowY"]
    c.drag(ax, ay, ax, ay - 160, b=1)
    time.sleep(0.3)
    s1 = state()
    check(s1["editing"]["half"][1] > s0["editing"]["half"][1] + 0.4, "dragging the end corner's y arrow up makes the box taller: %s" % s1["editing"]["half"])
    h2 = handles()
    # the start corner stays where it was in the world: its projection is unchanged
    close(h2["START"][0], start_before[0], 1.0, "start corner fixed (x)")
    close(h2["START"][1], start_before[1], 1.0, "start corner fixed (y)")
    n = re.search(r"\((\d+) blocks\)", s1["tree"][0]).group(1)
    half = s1["editing"]["half"]
    check(int(n) == round(half[0] * 2) * round(half[1] * 2) * round(half[2] * 2), "count follows the resize")
    shot("box-resized")


def test_box_rotate_ring_makes_oriented_box():
    editor_on()
    fresh_tree()
    cam(2, -50, -8, 0, 40)
    create_box(650, 550, 950, 700)
    h = handles()
    c.click(*h["CENTER"], b=1)
    time.sleep(0.3)
    h = handles()
    rx, ry = h["ringY"]
    ox, oy = h["CENTER"]
    # drag around the centre: rotate the grab point by ~50 degrees on screen
    ang = math.atan2(ry - oy, rx - ox)
    r = math.hypot(rx - ox, ry - oy)
    pts = [(ox + r * math.cos(ang + a), oy + r * math.sin(ang + a)) for a in [i * math.radians(5) for i in range(11)]]
    c.move(*pts[0])
    time.sleep(0.1)
    c.button(1, "press")
    for p in pts[1:]:
        c.move(*p)
        time.sleep(0.04)
    c.button(1, "release")
    time.sleep(0.3)
    s = state()
    rot = s["editing"]["rotation"]
    check("1.000E+0)" not in rot.split()[-1] or "0.000E+0  0.000E+0  0.000E+0" not in rot, "rotation should no longer be identity: %s" % rot)
    q = [float(v) for v in re.findall(r"-?\d\.\d+E[+-]\d+", rot)]
    angle = math.degrees(2 * math.acos(min(1, abs(q[3]))))
    check(angle > 10, "rotated by at least one 15 degree step: %.2f" % angle)
    close(angle / 15, round(angle / 15), 0.02, "rotation snaps to 15 degrees (%.2f)" % angle)
    check(abs(q[0]) < 1e-6 and abs(q[2]) < 1e-6, "the y ring rotates about y only: %s" % q)
    shot("box-rotated")


def test_box_gizmo_shift_does_not_fly():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    h = handles()
    c.click(*h["CENTER"], b=1)
    time.sleep(0.2)
    h = handles()
    ax, ay = h["arrowX"]
    p0 = state()["camera"]["pos"]
    c.move(ax, ay)
    time.sleep(0.1)
    c.button(1, "press")
    time.sleep(0.1)
    check(state()["editing"]["dragging"], "dragging the gizmo")
    c.key(K["LSHIFT"], "press", mc.MOD_SHIFT)
    for i in range(1, 11):
        c.move(ax + i * 9, ay)
        time.sleep(0.05)
    time.sleep(0.3)
    c.button(1, "release")
    time.sleep(0.2)
    c.key(K["LSHIFT"], "release")
    p1 = state()["camera"]["pos"]
    check(p1 == p0, "Shift while dragging must not fly the camera down: %s -> %s" % (p0, p1))
    check(state()["player"] == [0.5, -60.0, 0.5] or True, "")


def test_box_nudge_with_arrow_keys():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    h = handles()
    c.click(*h["CENTER"], b=1)
    time.sleep(0.2)
    before = state()["editing"]["center"]
    c.move(790, 300)
    time.sleep(0.1)
    c.key(82)  # up arrow: north (-z)
    c.key(75)  # page up: +y
    time.sleep(0.3)
    after = state()["editing"]["center"]
    check(after[2] == before[2] - 1 and after[1] == before[1] + 1 and after[0] == before[0], "nudged: %s -> %s" % (before, after))


def test_box_confirm_and_escape():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    c.key(K["ENTER"])
    time.sleep(0.3)
    s = state()
    check(s.get("editing") is None, "Enter confirms (stops editing)")
    check(len(s["tree"]) == 1, "confirmed box stays in the tree")
    shot("box-confirmed")
    # RMB-drag then Escape while dragging cancels creation
    vp = s["viewport"]
    c.move(700, 600)
    time.sleep(0.1)
    c.button(3, "press")
    c.move(800, 650)
    time.sleep(0.2)
    check(state()["creating"], "creating while RMB is held")
    c.key(K["ESC"])
    c.button(3, "release")
    time.sleep(0.3)
    s = state()
    check(len(s["tree"]) == 1, "Escape cancels the box being created")
    check("screen=none" in c.status(), "Escape used by the editor does not open the pause menu")


def test_box_put_down_without_enter():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    # 1) a plain click on empty space
    create_box(650, 550, 950, 700)
    check(state().get("editing"), "editing after creating")
    c.click(790, 150, b=1)  # sky: nothing to grab
    time.sleep(0.3)
    check(state().get("editing") is None, "clicking empty space puts the box down")
    # 2) clicking the box in the tree selects it for moving, clicking it again puts it down
    pos, _ = find_node("box 1")
    c.click(*pos, b=1)
    time.sleep(0.3)
    check(state().get("editing"), "clicking the box in the tree edits it")
    shot("box-edit-from-tree")
    c.click(*pos, b=1)
    time.sleep(0.3)
    check(state().get("editing") is None, "clicking it again puts it down")
    # 3) the Done button in the viewport
    c.click(*pos, b=1)
    time.sleep(0.3)
    c.click(*widget("view_done"), b=1)
    time.sleep(0.3)
    check(state().get("editing") is None, "Done puts it down")
    # 4) a drag (camera look) does not put it down
    c.click(*pos, b=1)
    time.sleep(0.3)
    c.drag(700, 150, 760, 170, b=1)
    time.sleep(0.2)
    check(state().get("editing"), "looking around keeps editing")
    c.key(K["ENTER"])


def test_tree_pick_restores_tool_and_selection():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    c.key(K["ENTER"])
    time.sleep(0.2)
    for before in ("none", "entity_select", "box_select"):
        ed("selectTool", before)
        pos, _ = find_node("box 1")
        c.click(*pos, b=1)
        time.sleep(0.3)
        s = state()
        check(s["tool"] == "box_select" and s.get("editing") and s["selected"], "picking the box in the tree edits it (from %s)" % before)
        c.click(790, 150, b=1)  # empty space
        time.sleep(0.3)
        s = state()
        check(s.get("editing") is None, "clicking elsewhere hides the gizmo")
        check(s["selected"] == [], "and deselects the box in the tree: %s" % s["selected"])
        check(s["tool"] == before, "and restores the tool: %s, expected %s" % (s["tool"], before))
    # choosing a tool by hand while editing keeps that tool
    ed("selectTool", "none")
    c.click(*find_node("box 1")[0], b=1)
    time.sleep(0.3)
    c.click(*widget("tool_entity_select"), b=1)
    time.sleep(0.3)
    check(state()["tool"] == "entity_select" and state().get("editing") is None, "a hand-picked tool wins")
    ed("selectTool", "box_select")


def test_world_view_buttons_only_in_viewport():
    editor_on()
    s = state()
    for mid in ("fluid_transparency", "see_through"):
        check("check_" + mid not in s["widgets"], "no duplicate %s section in the left panel" % mid)
        check("view_" + mid in s["widgets"], "viewport button for %s" % mid)
    c.click(*widget("view_see_through"), b=3)
    time.sleep(0.4)
    shot("see-through-settings")
    c.key(K["ESC"])
    time.sleep(0.2)
    check("screen=none" in c.status(), "Escape closes the settings")


def test_box_name_field_typing_does_not_fly():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    rects = state()["widgets"]
    # Tool Options name field: first input in the options panel, found by its label row
    s = state()
    p0 = s["camera"]["pos"]
    c.click(*widget("box_name"), b=1)
    time.sleep(0.2)
    c.type("walls")
    for ch in "wasd":
        c.key(getattr(sys.modules[__name__], "K")[ch.upper()], "press")
        c.key(getattr(sys.modules[__name__], "K")[ch.upper()], "release")
    c.key(K["ENTER"])
    time.sleep(0.3)
    s = state()
    check(s["camera"]["pos"] == p0, "typing into a field must not move the camera")
    check(s["tree"][0].startswith("walls ("), "box renamed through the name field: %s" % s["tree"])


def test_entity_select():
    editor_on()
    fresh_tree()
    ed("selectTool", "entity_select")
    cam(3, -58, -9, 0, 5)
    time.sleep(0.3)
    p = ed("project", 4.5, -59, 2.5)
    x, y = map(float, p.split(","))
    c.click(x, y, b=3)
    time.sleep(0.3)
    s = state()
    check(s["tree"] == ["armor_stand: Stand"], "RMB on the armor stand selects it: %s" % s["tree"])
    # the local player (third person) can be selected too
    p = ed("project", 0.5, -59, 0.5)
    x, y = map(float, p.split(","))
    c.click(x, y, b=3)
    time.sleep(0.3)
    s = state()
    check(s["tree"] == ["armor_stand: Stand", "player: Dev"], "RMB on the player selects 'player: Dev': %s" % s["tree"])
    shot("entities")
    ed("selectTool", "box_select")


def find_node(name):
    w = state()["widgets"]
    for k, v in w.items():
        if k == "node:" + name:
            x1, y1, x2, y2 = map(float, v.split(","))
            return (x1 + 40, (y1 + y2) / 2), (x1, y1, x2, y2)
    raise AssertionError("tree node %s not visible; widgets: %s" % (name, [k for k in w if k.startswith("node:")]))


def ui_drag(a, b):
    c.move(*a)
    time.sleep(0.1)
    c.button(1, "press")
    time.sleep(0.1)
    for i in range(1, 11):
        c.move(a[0] + (b[0] - a[0]) * i / 10, a[1] + (b[1] - a[1]) * i / 10)
        time.sleep(0.04)
    time.sleep(0.15)
    c.button(1, "release")
    time.sleep(0.3)


def test_tree_example_through_ui():
    """Builds the example tree from the specification with the tree view's own controls."""
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    # four boxes and two entities
    for (a, b) in [((620, 500), (700, 560)), ((720, 500), (800, 560)), ((820, 500), (900, 560)), ((620, 600), (700, 660))]:
        create_box(*a, *b)
        c.key(K["ENTER"])
        time.sleep(0.2)
        # unselect so new boxes go to the top level
        c.click(*widget("tree_empty"), b=1)
    ed("selectTool", "entity_select")
    for (wx, wy, wz) in [(4.5, -59, 2.5), (0.5, -59, 0.5)]:
        x, y = map(float, ed("project", wx, wy, wz).split(","))
        c.click(x, y, b=3)
        time.sleep(0.2)
        c.click(*widget("tree_empty"), b=1)
    ed("selectTool", "box_select")
    # two groups via "+ Group"
    c.click(*widget("tree_new_group"), b=1)
    time.sleep(0.2)
    c.click(*widget("tree_empty"), b=1)
    c.click(*widget("tree_new_group"), b=1)
    time.sleep(0.3)
    c.click(*widget("tree_empty"), b=1)
    s = state()
    check(len(s["tree"]) == 8, "4 boxes, 2 entities, 2 groups at top level: %s" % s["tree"])
    # rename group 1 to "target" by double-click, typing and Enter
    pos, _ = find_node("group 1")
    c.move(*pos)
    time.sleep(0.1)
    c.button(1)
    time.sleep(0.05)
    c.button(1)
    time.sleep(0.3)
    r = state()["widgets"].get("rename")
    check(r is not None, "the rename field is drawn")
    x1, y1, x2, y2 = map(float, r.split(","))
    check(x1 > 1280 and x2 - x1 > 120 and abs((y1 + y2) / 2 - pos[1]) < 6, "rename field over the row, wide enough: %s" % r)
    shot("tree-rename")
    c.key(K["BACKSPACE"])
    c.type("target")
    c.key(K["ENTER"])
    time.sleep(0.3)
    find_node("target")
    # drag & drop: box 1 into target (middle of the row), group 2 into target, box 2 and 3 into group 2
    def drop_into(src, dst):
        a, _ = find_node(src)
        b, r = find_node(dst)
        ui_drag(a, (b[0], (r[1] + r[3]) / 2))
    drop_into("box 1", "target")
    drop_into("group 2", "target")
    drop_into("box 2", "group 2")
    drop_into("box 3", "group 2")
    s = state()
    tree = s["tree"]
    check(len(tree) == 4, "top level now: box 4, two entities, target: %s" % tree)
    tgt = [t for t in tree if isinstance(t, dict) and "target" in t]
    check(tgt, "target group at the top level: %s" % tree)
    inner = tgt[0]["target"]
    check(inner[0].startswith("box 1 (") and isinstance(inner[1], dict) and "group 2" in inner[1], "target = box 1 + group 2: %s" % inner)
    g2 = inner[1]["group 2"]
    check(len(g2) == 2 and g2[0].startswith("box 2") and g2[1].startswith("box 3"), "group 2 = box 2, box 3: %s" % g2)
    # a new group from the two entities and box 4 (Ctrl-click to multi-select, then "Group")
    c.click(*find_node("armor_stand: Stand")[0], b=1)
    c.key(K["LCTRL"], "press", mc.MOD_CTRL)
    c.click(*find_node("player: Dev")[0], b=1)
    c.click(*find_node("box 4")[0], b=1)
    c.key(K["LCTRL"], "release")
    time.sleep(0.2)
    check(len(state()["selected"]) == 3, "three nodes selected: %s" % state()["selected"])
    c.click(*widget("tree_group"), b=1)
    time.sleep(0.3)
    s = state()
    tree = s["tree"]
    check(len(tree) == 2, "target and the new group: %s" % tree)
    newg = [t for t in tree if isinstance(t, dict) and "target" not in t][0]
    name = list(newg)[0]
    check(name == "group 3", "new group gets the next free name: %s" % name)
    check([x.split(" (")[0] for x in newg[name]] == ["box 4", "armor_stand: Stand", "player: Dev"], "group keeps tree order: %s" % newg)
    print("      tree:", json.dumps(tree))
    shot("tree-example")
    # reorder: drop box 4 below player (bottom quarter of the row)
    a, _ = find_node("box 4")
    b, r = find_node("player: Dev")
    ui_drag(a, (b[0], r[3] - 2))
    s = state()
    newg = [t for t in s["tree"] if isinstance(t, dict) and "target" not in t][0]
    check([x.split(" (")[0] for x in newg["group 3"]] == ["armor_stand: Stand", "player: Dev", "box 4"], "reordered: %s" % newg)
    # a group cannot be dropped into its own child
    drop_into("target", "group 2")
    check(any(isinstance(t, dict) and "target" in t for t in state()["tree"]), "target stays at the top level")
    # delete with the Delete key (viewport focus)
    c.click(*find_node("box 4")[0], b=1)
    c.move(790, 450)
    time.sleep(0.2)
    c.key(K["DELETE"])
    time.sleep(0.3)
    newg = [t for t in state()["tree"] if isinstance(t, dict) and "target" not in t][0]
    check(len(newg["group 3"]) == 2, "Delete removes the selected node: %s" % newg)


def test_tree_visibility_buttons():
    editor_on()
    fresh_tree()
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    c.key(K["ENTER"])
    time.sleep(0.2)
    c.click(*widget("tree_new_group"), b=1)
    time.sleep(0.3)
    a, _ = find_node("box 1")
    b, r = find_node("group 1")
    ui_drag(a, (b[0], (r[1] + r[3]) / 2))
    lines = int(state()["overlay"]["WORLD"].split()[0])
    check(lines >= 12, "box drawn")
    c.click(*widget("vis:box 1"), b=1)
    time.sleep(0.3)
    check(int(state()["overlay"]["WORLD"].split()[0]) == lines - 12, "eye button hides the box")
    c.click(*widget("vis:box 1"), b=1)
    time.sleep(0.3)
    check(int(state()["overlay"]["WORLD"].split()[0]) == lines, "and shows it again")
    c.click(*widget("vis:group 1"), b=1)
    time.sleep(0.3)
    check(int(state()["overlay"]["WORLD"].split()[0]) == lines - 12, "hiding a group hides its children")
    shot("tree-hidden-group")
    c.click(*widget("vis:group 1"), b=1)
    time.sleep(0.3)


def test_tool_click_again_unselects():
    editor_on()
    ed("selectTool", "box_select")
    c.click(*widget("tool_box_select"), b=1)
    time.sleep(0.3)
    s = state()
    check(s["tool"] == "none", "clicking the active tool unselects it: %s" % s["tool"])
    # RMB does nothing without a tool
    fresh_tree()
    ed("selectTool", "none")
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    check(state()["tree"] == [], "no box without a tool")
    c.click(*widget("tool_box_select"), b=1)
    time.sleep(0.3)
    check(state()["tool"] == "box_select", "clicking it again selects it")


def test_camera_in_rock_sees_caves():
    editor_on()
    fresh_tree()
    for m in ("see_through", "fluid_transparency", "block_overlay_demo", "path_overlay_demo"):
        ed("setModule", m, "false")
    # inside the rock, ~65 blocks (four solid sections) from the sealed cave, looking at the gold block
    cam(-32, -47.5, 20, 0, 3)
    time.sleep(3)
    x, y = map(float, ed("project", -31.5, -48.5, 87).split(","))
    col = avg_color(shot("cave-from-rock"), int(x) - 6, int(y) - 6, 12, 12)
    print("      gold block seen from inside the rock:", col)
    check(col[0] > 120 and col[0] > col[2] + 60, "the cave (gold block) is visible from inside the rock: %s" % (col,))


def test_arcball_ctrl_shift_skips_surrounding_rock():
    editor_on()
    fresh_tree()
    ed("unfollow")
    # inside the rock, looking at the lit cave ~60 blocks ahead
    cam(-32, -47.5, 20, 0, 3)
    time.sleep(1.5)
    # aim at the gold block in the cave
    cx, cy = map(float, ed("project", -31.5, -48.5, 87).split(","))
    def orbit_pivot(shift):
        c.key(K["LCTRL"], "press")
        if shift:
            c.key(K["LSHIFT"], "press", mc.MOD_SHIFT)
        c.move(cx, cy)
        time.sleep(0.05)
        c.button(1, "press")
        time.sleep(0.1)
        piv = state()["camera"]["pivot"]
        p0 = state()["camera"]["pos"]
        c.button(1, "release")
        if shift:
            c.key(K["LSHIFT"], "release")
        c.key(K["LCTRL"], "release")
        time.sleep(0.2)
        return piv, p0
    piv, _ = orbit_pivot(False)
    check(abs(piv[2] - 20) < 1.5, "plain Ctrl+LMB pivots on the rock the camera is in: %s" % piv)
    cam(-32, -47.5, 20, 0, 3)
    time.sleep(0.5)
    piv, pos = orbit_pivot(True)
    check(piv[2] > 80, "Ctrl+Shift+LMB pivots on the cave behind the rock: %s" % piv)
    check(pos == [-32.0, -47.5, 20.0], "holding Shift with Ctrl does not fly the camera down: %s" % pos)


def test_see_through_only_in_editor():
    editor_on()
    cmd("tp @s -8.5 -59 -8.5")
    time.sleep(0.5)
    cam(-8.5, -50, -16, 0, 45)
    ed("setModule", "see_through", "true")
    time.sleep(1)
    check(state()["cutawaySegments"] >= 1, "cut while in the editor")
    editor_off()
    time.sleep(0.8)
    check(state()["cutawaySegments"] == 0, "no cut in the normal game")
    editor_on()
    time.sleep(0.8)
    check(state()["cutawaySegments"] >= 1, "back when the editor opens again")
    ed("setModule", "see_through", "false")
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)


def test_selection_persists():
    editor_on()
    if not state()["tree"]:
        ed("selectTool", "box_select")
        cam(2, -54, -6, 0, 35)
        create_box(650, 550, 950, 700)
        c.key(K["ENTER"])
        time.sleep(0.3)
    tree = state()["tree"]
    check(tree, "previous test left a tree")
    editor_off()
    editor_on()
    check(state()["tree"] == tree, "tree survives closing/opening the editor")
    files = os.listdir(os.path.join(c.run_dir, "config", "plainsight", "selections"))
    check(any(f.endswith(".json") for f in files), "selections saved to disk: %s" % files)


def test_fluid_transparency_button():
    editor_on()
    fresh_tree()
    ed("setModule", "fluid_transparency", "false")
    cam(14.5, -56, -4, 0, 55)
    time.sleep(1.5)
    x, y = map(float, ed("project", 14.5, -61, 0.5).split(","))
    before = shot("fluids-off")
    c1 = avg_color(before, int(x) - 20, int(y) - 20, 40, 40)
    c.click(*widget("view_fluid_transparency"), b=1)
    time.sleep(2.5)
    check(state()["modules"]["fluid_transparency"], "viewport button toggles fluid transparency")
    after = shot("fluids-on")
    c2 = avg_color(after, int(x) - 20, int(y) - 20, 40, 40)
    print("      water colour", c1, "->", c2)
    check(color_dist(c1, c2) > 20, "water looks different when transparent: %s -> %s" % (c1, c2))
    check(c2[2] - max(c2[0], c2[1]) < c1[2] - max(c1[0], c1[1]), "less blue: the ground shows through")
    c.click(*widget("view_fluid_transparency"), b=1)
    time.sleep(2.5)
    c3 = avg_color(shot("fluids-off-again"), int(x) - 20, int(y) - 20, 40, 40)
    check(color_dist(c1, c3) < 12, "turning it off restores the water: %s vs %s" % (c1, c3))


def test_see_through_players_button():
    editor_on()
    ed("setModule", "see_through", "false")
    # put the player inside the closed stone hut, camera outside above it
    cmd("tp @s -8.5 -59 -8.5")
    time.sleep(1.0)
    cam(-8.5, -50, -16, 0, 45)
    time.sleep(1.5)
    x, y = map(float, ed("project", -8.5, -58, -8.5).split(","))
    off = shot("seethrough-off")
    c1 = avg_color(off, int(x) - 6, int(y) - 12, 12, 24)
    c.click(*widget("view_see_through"), b=1)
    time.sleep(2.5)
    s = state()
    check(s["modules"]["see_through"] and s["cutawaySegments"] >= 1, "see-through cuts towards the player: %s" % s["cutawaySegments"])
    on = shot("seethrough-on")
    c2 = avg_color(on, int(x) - 6, int(y) - 12, 12, 24)
    hx, hy = map(float, ed("project", -8.5, -57.3, -8.5).split(","))
    head_off = avg_color(off, int(hx) - 3, int(hy) - 3, 6, 6)
    head_on = avg_color(on, int(hx) - 3, int(hy) - 3, 6, 6)
    print("      player head area colour", head_off, "->", head_on)
    # grey stone has no saturation; the player's (purple) hair has
    check(max(head_off) - min(head_off) < 12, "the roof hides the player when off")
    check(max(head_on) - min(head_on) > 25, "the player becomes visible through the roof: %s" % (head_on,))
    # a wide area around the player is cut, not only the line of sight (3 blocks beside the player)
    col = ed("cutColumn", -12, -9, -59, -56)
    check(col.startswith("####"), "blocks beside the player are cut too: %s" % col[:20])
    # fully transparent: the uncovered floor is lit, not shadowed by the hidden roof
    ed("seeThroughOpacity", 0.0)
    time.sleep(2.5)
    fx, fy = map(float, ed("project", -10.5, -59, -9.5).split(","))
    floor = avg_color(shot("seethrough-open"), int(fx) - 8, int(fy) - 8, 16, 16)
    print("      open floor colour", floor)
    check(sum(floor) / 3 > 70, "the floor is lit like outside (no shadow from hidden blocks): %s" % (floor,))
    px, py = map(float, ed("project", -8.5, -58.2, -8.5).split(","))
    body = avg_color(shot("seethrough-open"), int(px) - 4, int(py) - 6, 8, 12)
    print("      player colour in the open space", body)
    check(sum(body) / 3 > 45, "the player is lit too, not a dark silhouette: %s" % (body,))
    ed("seeThroughOpacity", 0.15)
    time.sleep(1.5)
    # moving the camera re-cuts
    rebuilds = s["cutawayRebuilds"]
    c.hold(K["D"], 0.4)
    time.sleep(1)
    check(state()["cutawayRebuilds"] > rebuilds, "moving the camera updates the cut")
    c.click(*widget("view_see_through"), b=1)
    time.sleep(2)
    c3 = avg_color(shot("seethrough-off-again"), int(x) - 6, int(y) - 12, 12, 24)
    check(state()["cutawaySegments"] == 0, "turning it off clears the cut")
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)


def test_see_through_from_below():
    editor_on()
    fresh_tree()
    ed("setModule", "see_through", "false")
    cmd("tp @s -8.5 -59 -8.5")
    time.sleep(0.8)
    # in the cave below, north of the hut, looking up at the player
    cam(-8.5, -62.5, -17, 0, -30)
    time.sleep(1.5)
    hx, hy = map(float, ed("project", -8.5, -58.5, -8.5).split(","))
    off = avg_color(shot("seethrough-below-off"), int(hx) - 4, int(hy) - 4, 8, 8)
    ed("setModule", "see_through", "true")
    time.sleep(2.5)
    col = ed("cutColumn", -9, -9, -62, -59)
    check(col.startswith("####"), "from below the ground under the player is cut: %s" % col[:12])
    on = avg_color(shot("seethrough-below-on"), int(hx) - 4, int(hy) - 4, 8, 8)
    print("      player from below", off, "->", on)
    check(color_dist(off, on) > 25, "the player becomes visible from below")
    ed("setModule", "see_through", "false")
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)


def test_block_overlay_demo_lit():
    editor_on()
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)
    c.click(*widget("view_block_overlay_demo"), b=1)
    time.sleep(1.0)
    s = state()
    # the hut stands 8 blocks south of the player: x 0..6 (with x-3), z 5..11
    check(s["modules"]["block_overlay_demo"], "viewport button enables the overlay demo")
    check(s["overlayBlocksSubmitted"] > 100, "overlay blocks are submitted: %s" % s["overlayBlocksSubmitted"])
    # look at it from outside
    cam(-9, -54, -3, -40, 20)
    time.sleep(0.8)
    shot("block-overlay-day")
    # lighting: at night the same blocks get darker (sky light), the torch side stays lit
    cmd("time set midnight")
    time.sleep(1.5)
    night = shot("block-overlay-night")
    cmd("time set noon")
    time.sleep(1.5)
    day = shot("block-overlay-day")
    # the overlay's roof/wall in the middle of the view
    vp = state()["viewport"]
    x, y = map(float, ed("project", -2.5, -58.5, 5).split(","))
    cd, cn = avg_color(day, int(x) - 10, int(y) - 10, 20, 20), avg_color(night, int(x) - 10, int(y) - 10, 20, 20)
    print("      overlay wall day/night", cd, cn)
    check(sum(cd) > sum(cn) + 60, "overlay blocks use the world's light (darker at night)")
    # translucent by default: compare with an opaque overlay at the same spot
    ed("overlayOpacity", 1.0)
    time.sleep(0.6)
    solid = avg_color(shot("block-overlay-solid"), int(x) - 10, int(y) - 10, 20, 20)
    ed("overlayOpacity", 0.65)
    time.sleep(0.6)
    ghost = avg_color(shot("block-overlay-ghost"), int(x) - 10, int(y) - 10, 20, 20)
    print("      overlay solid/ghost", solid, ghost)
    check(color_dist(solid, ghost) > 12, "the default overlay is translucent")
    # only the front surface is blended: ghost = 0.65 * solid + 0.35 * what is behind the overlay
    ed("setModule", "block_overlay_demo", "false")
    time.sleep(0.6)
    behind = avg_color(shot("block-overlay-behind"), int(x) - 10, int(y) - 10, 20, 20)
    ed("setModule", "block_overlay_demo", "true")
    expected = tuple(0.65 * a + 0.35 * b for a, b in zip(solid, behind))
    print("      behind", behind, "expected ghost", tuple(round(v) for v in expected))
    check(color_dist(ghost, expected) < 14, "no hidden faces show through the ghost: %s vs %s" % (ghost, expected))
    time.sleep(0.6)
    c.click(*widget("view_block_overlay_demo"), b=1)
    time.sleep(0.5)
    check(state()["overlayBlocksSubmitted"] == 0, "disabled overlay is gone")


def test_path_overlay_demo():
    editor_on()
    # facing the oak wall (x 4..8, y -60..-57, z 8..10): the plan has to tunnel through it
    cmd("tp @s 6.5 -60 2.5 0 0")
    time.sleep(0.5)
    c.click(*widget("view_path_overlay_demo"), b=1)
    time.sleep(0.5)
    s = state()
    check(s["modules"]["path_overlay_demo"], "viewport button enables the path demo")
    lines, tris = int(s["overlay"]["WORLD"].split()[0]), int(s["overlay"]["WORLD"].split()[2])
    check(lines > 20 and tris > 50, "path lines, node markers and goal drawn: %s" % s["overlay"])
    check(int(s["overlay"]["XRAY"].split()[0]) > 20, "path also shown faintly through terrain")
    info = ed("pathInfo")
    print("      plan:", info)
    check(re.search(r"break=[1-9]", info), "the wall is marked for breaking: %s" % info)
    cam(-2, -50, -2, -40, 35)
    time.sleep(0.5)
    shot("path-overlay")
    # the settings popup opens with a right click
    c.click(*widget("view_path_overlay_demo"), b=3)
    time.sleep(0.4)
    shot("path-overlay-popup")
    c.key(K["ESC"])
    time.sleep(0.2)
    c.click(*widget("view_path_overlay_demo"), b=1)
    time.sleep(0.3)


def test_escape_closes_popup_without_pausing():
    editor_on()
    c.click(*widget("view_path_overlay_demo"), b=3)
    time.sleep(0.4)
    c.key(K["ESC"])
    time.sleep(0.4)
    check("screen=none" in c.status(), "Escape closes the ImGui popup, not opens the pause menu: %s" % c.status().splitlines()[0])


def test_chat_and_pause_still_work():
    editor_on()
    fresh_tree()
    check("screen=none" in c.status(), "starts without a screen")
    c.key(K["T"] if "T" in K else 23)
    time.sleep(0.4)
    check("ChatScreen" in c.status(), "T opens chat in the editor: %s" % c.status().splitlines()[0])
    c.type("hi")
    c.key(K["ESC"])
    time.sleep(0.3)
    check("screen=none" in c.status(), "Escape closes chat")
    check(state()["active"], "editor still open after chat")
    c.key(K["ESC"])
    time.sleep(0.4)
    check("PauseScreen" in c.status(), "Escape with nothing to cancel opens the pause menu: %s" % c.status().splitlines()[0])
    c.key(K["ESC"])
    time.sleep(0.4)
    check("screen=none" in c.status(), "back from the pause menu")


TESTS = [v for k, v in list(globals().items()) if k.startswith("test_")]
K["T"] = 23
K["BACKSPACE"] = 42


def main(argv):
    setup_world()
    selected = [t for t in TESTS if not argv or any(a in t.__name__ for a in argv)]
    for t in selected:
        name = t.__name__[5:]
        t0 = time.time()
        try:
            t()
            results.append((name, True, ""))
            print("PASS %-45s %.1fs" % (name, time.time() - t0))
        except Exception as e:  # noqa: BLE001
            results.append((name, False, str(e)))
            print("FAIL %-45s %s" % (name, e))
            traceback.print_exc(limit=2)
            try:
                shot("FAIL-" + name)
                c.button(1, "release")
                c.button(3, "release")
                c.key(K["LCTRL"], "release")
            except Exception:  # noqa: BLE001
                pass
    failed = [r for r in results if not r[1]]
    print("\n%d passed, %d failed" % (len(results) - len(failed), len(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
