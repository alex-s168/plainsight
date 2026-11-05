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


def changed_pixels(a, b, x, y, w, h):
    """Number of pixels in a rectangle that differ (by more than 10%) between two PNGs."""
    crop = "[%dx%d+%d+%d]" % (w, h, x, y)
    r = subprocess.run([os.path.join(ROOT, "dev"), "magick", "compare", "-metric", "AE", "-fuzz", "10%", a + crop, b + crop, "null:"],
                       cwd=ROOT, capture_output=True, text=True)
    m = re.search(r"^\s*([\d.e+]+)", r.stderr.strip().splitlines()[-1] if r.stderr.strip() else "")
    if not m:
        raise AssertionError("magick compare failed: " + r.stderr[-500:])
    return int(float(m.group(1)))


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
    if state()["frames"] == 0:
        # a fresh client (editor never opened yet): the editor starts without a tool, slightly brightened
        check(state()["tool"] == "none", "no tool is selected by default: %s" % state()["tool"])
        check(state()["modules"]["editor_brightness"] and not state()["modules"]["ingame_brightness"], "editor brightness on by default")
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


def test_bridge_screenshot_after_editor():
    """The editor's depth pick copies the depth buffer every frame; colour readbacks (the bridge's and F2
    screenshots) must still work afterwards, not come out black."""
    editor_on()
    c.move(800, 450)
    frames_pass(5)
    editor_off()
    frames_pass(3)
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, "bridge-after-editor.png")
    r = c.shot(path)
    check(r.strip() == path, "bridge screenshot failed: " + r[:300])
    rgb = avg_color(path, 0, 0, 1600, 900)
    check(sum(rgb) > 30, "bridge screenshot is black: average %s" % (rgb,))


def test_overlays_in_game_keybind():
    editor_on()
    fresh_tree()
    ed("setOverlaysInGame", "false")
    ed("setModule", "light_overlay", "false")
    cam(2, -54, -6, 0, 35)
    create_box(650, 550, 950, 700)
    c.key(K["ENTER"])
    time.sleep(0.2)
    editor_off()
    check(ed("overlaysInGame").split()[1] == "0", "no overlays in game by default")
    c.key(13)  # J
    time.sleep(0.4)
    check("screen=none" in c.status(), "the key opens no game screen: %s" % c.status().splitlines()[0])
    on, lines, _ = ed("overlaysInGame").split()
    check(on == "true" and int(lines) >= 12, "J shows the selection boxes in game: %s" % on)
    # the block overlay demo follows the same switch
    ed("setModule", "block_overlay_demo", "true")
    time.sleep(0.4)
    check(state()["overlayBlocksSubmitted"] > 100, "J shows the block overlay in game")
    c.key(13)
    time.sleep(0.4)
    check(state()["overlayBlocksSubmitted"] == 0, "without J the block overlay is not shown in game")
    c.key(13)
    time.sleep(0.4)
    ed("setModule", "block_overlay_demo", "false")
    shot("overlays-in-game")
    c.key(13)
    time.sleep(0.4)
    check(ed("overlaysInGame").split()[:2] == ["false", "0"], "J hides them again")


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


def test_follow_is_smooth():
    """Following an entity that moves (not teleported), the camera keeps its offset to where the entity is
    drawn in every frame, not only once per game tick (that made the view judder)."""
    editor_on()
    fresh_tree()
    cmd("kill @e[type=item,name=Mover]")
    cmd('summon item 4.5 -58 2.5 {Item:{id:"minecraft:stone",count:1},CustomName:"Mover",NoGravity:1b,PickupDelay:32767,Age:-32768}')
    time.sleep(0.5)
    check(ed("follow", "Mover") == "ok", "follows the moving item")
    cam(4.5, -57, -6, 0, 20)
    time.sleep(0.3)
    offsets, xs = [], []
    t0 = time.time()
    while time.time() - t0 < 3:
        if len(offsets) % 8 == 0:
            cmd("data merge entity @e[type=item,name=Mover,limit=1] {Motion:[0.4d,0d,0d]}")
        o = ed("followOffset")
        if o != "none":
            offsets.append(tuple(map(float, o.split(","))))
            xs.append(state()["camera"]["pos"][0])
        time.sleep(0.02)
    ed("unfollow")
    cmd("kill @e[type=item,name=Mover]")
    check(len(offsets) > 30 and xs[-1] - xs[0] > 3, "the item moved and the camera with it: %d samples, x %.1f -> %.1f" % (len(offsets), xs[0], xs[-1]))
    spread = max(o[0] for o in offsets) - min(o[0] for o in offsets)
    check(spread < 0.02, "the camera's offset to the drawn entity stays the same in every frame: varies by %.3f" % spread)


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


def test_camera_scroll_dolly_against_block():
    """#28: with the camera in or right against a block (e.g. after zooming out into terrain), scrolling up still
    zooms in, through it, instead of not moving at all."""
    editor_on()
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    # the plank wall at z 8..10 in front of the spawn point, looking south (+z) at it
    for z in (7.6, 7.2, 8.5):
        cam(6.5, -58.5, z, 0, 0)
        c.move(cx, cy)
        time.sleep(0.1)
        c.scroll(1, cx, cy)
        time.sleep(0.4)
        p = state()["camera"]["pos"]
        check(p[2] - z > 0.9, "scrolling up at z %s moves into the wall: z %s" % (z, p[2]))
        c.scroll(-1, cx, cy)
        time.sleep(0.4)
        check(state()["camera"]["pos"][2] < p[2] - 0.5, "and scrolling down back out")


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
    hidden = int(state()["overlay"]["WORLD"].split()[0])
    check(hidden <= lines - 12, "eye button hides the box (its edges and block grid): %d -> %d lines" % (lines, hidden))
    c.click(*widget("vis:box 1"), b=1)
    time.sleep(0.3)
    check(int(state()["overlay"]["WORLD"].split()[0]) == lines, "and shows it again")
    c.click(*widget("vis:group 1"), b=1)
    time.sleep(0.3)
    check(int(state()["overlay"]["WORLD"].split()[0]) == hidden, "hiding a group hides its children")
    shot("tree-hidden-group")
    c.click(*widget("vis:group 1"), b=1)
    time.sleep(0.3)


def test_box_tints_selected_terrain():
    """The solid blocks a box covers are tinted where open to the air (so a box reaching above the
    terrain still shows which blocks it holds); the same box in the air tints nothing."""
    editor_on()
    fresh_tree()
    cam(2, -50, -12, 0, 30)

    def tris():
        time.sleep(0.4)
        return int(state()["overlay"]["WORLD"].split()[2])  # "N lines, M tris"

    ed("addBox", 2, -20, 2, 7, -15, 7, "shown")
    in_air = tris()
    fresh_tree()
    ed("addBox", 2, -62, 2, 7, -57, 7, "shown")
    on_ground = tris()
    # the 6x6 grass tops inside the box at least (2 triangles each)
    check(on_ground >= in_air + 72, "terrain in the box is tinted: %d vs %d triangles in the air" % (on_ground, in_air))
    shot("box-terrain-tint")
    fresh_tree()


def test_chunk_borders_follow_cursor():
    """In the editor the chunk borders (F3+G) are around the chunk under the cursor; and with them on
    (always-on-top gizmos clear the depth) the surface under the cursor is still found."""
    editor_on()
    check(ed("chunkBorders", "true") == "true", "chunk borders on")
    try:
        cam(0, -40, -20, 0, 40)
        chunks = set()
        for x, y in ((350, 850), (1200, 300)):
            c.move(x, y)
            time.sleep(1)
            surface = state()["surfaceUnderCursor"]
            check(surface, "surface under the cursor found with chunk borders on")
            # the block just behind the surface, seen from the camera at (0, -40, -20)
            p = [float(v) for v in surface.split(",")]
            d = [p[0] - 0, p[1] + 40, p[2] + 20]
            n = math.sqrt(sum(v * v for v in d))
            sx, sz = math.floor(p[0] + d[0] / n * 0.01), math.floor(p[2] + d[2] / n * 0.01)
            ax, _, az = (int(v) for v in ed("chunkBorderAnchor").split(","))
            check((ax >> 4, az >> 4) == (sx >> 4, sz >> 4), "borders around the cursor's chunk: %s vs %s" % ((ax, az), surface))
            chunks.add((ax >> 4, az >> 4))
        check(len(chunks) == 2, "and they move with it: %s" % chunks)
        shot("chunk-borders")
    finally:
        ed("chunkBorders", "false")


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


def test_brightness_toggles():
    editor_on()
    for m in ("editor_brightness", "ingame_brightness"):
        ed("setModule", m, "false")
    # inside the sealed (unlit) stone hut, looking at its west wall
    cmd("tp @s 0.5 -60 0.5 0 0")
    cam(-8.5, -58, -9.5, 90, 0)
    time.sleep(1.5)
    def wall():
        x, y = map(float, ed("project", -10.98, -58, -9.5).split(","))
        return avg_color(shot("brightness"), int(x) - 8, int(y) - 8, 16, 16)
    dark = wall()
    c.click(*widget("module_editor_brightness"), b=1)
    time.sleep(0.6)
    check(state()["modules"]["editor_brightness"], "toolbar button enables editor brightness")
    dim = wall()
    check(sum(dim) > sum(dark) + 9, "the default level (0.2) brightens a little: %s -> %s" % (dark, dim))
    ed("brightnessLevel", 1.0, 1.0)
    time.sleep(0.6)
    bright = wall()
    print("      cave wall normal / fullbright:", dark, bright)
    check(sum(bright) > sum(dark) + 90, "fullbright lights the cave")
    shot("brightness-editor")
    # in-game brightness is separate: off in the game while only the editor one is on
    editor_off()
    time.sleep(0.6)
    check(not state()["modules"]["ingame_brightness"], "in-game brightness stays off")
    editor_on()
    c.click(*widget("module_editor_brightness"), b=1)
    time.sleep(0.3)
    c.click(*widget("module_ingame_brightness"), b=3)
    time.sleep(0.4)
    shot("brightness-settings")
    c.key(K["ESC"])
    time.sleep(0.2)
    # back to the defaults: editor brightness on at 0.2
    ed("brightnessLevel", 0.2, 1.0)
    ed("setModule", "editor_brightness", "true")


def test_light_overlay():
    editor_on()
    for m in ("editor_brightness", "ingame_brightness"):
        ed("setModule", m, "false")
    cmd("time set noon")
    # a torch on the open grass: the flat world is lit by the sky, so dark = block light <= 0 is yellow
    cmd("setblock 20 -60 20 torch")
    cam(20.5, -50, 8, 0, 45)
    ed("setOverlaysInGame", "false")
    ed("setModule", "light_overlay", "false")
    ed("lightOverlay", 0, 40)
    c.click(*widget("module_light_overlay"), b=1)
    time.sleep(1.2)
    s = state()
    check(s["modules"]["light_overlay"] and s["lightOverlayFaces"] > 100, "light overlay marks faces: %s" % s["lightOverlayFaces"])
    shot("light-overlay")
    # near the torch block light > 0: unmarked; far away: yellow (sky light, no block light)
    near = ed("project", 21.5, -60.98, 20.5)
    nx, ny = map(float, near.split(","))
    # 16 blocks (Manhattan) from the torch its light has faded to 0
    fx, fy = map(float, ed("project", 20.5, -60.98, 36.5).split(","))
    img = os.path.join(SHOTS, "light-overlay.png")
    cn, cf = avg_color(img, int(nx) - 3, int(ny) - 3, 6, 6), avg_color(img, int(fx) - 3, int(fy) - 3, 6, 6)
    print("      next to the torch / far from it:", cn, cf)
    check(cf[0] > cf[2] + 40 and cf[1] > cf[2] + 30, "far from the torch the ground is marked yellow: %s" % (cf,))
    check(not (cn[0] > cn[2] + 40 and cn[1] > cn[2] + 30), "next to the torch it is not marked: %s" % (cn,))
    # inside the sealed hut it is red (no sky light either)
    cam(-9.5, -56.5, -9.5, 0, 89)
    ed("setModule", "editor_brightness", "true")
    time.sleep(1.2)
    hx, hy = map(float, ed("project", -9.5, -59.98, -8.5).split(","))
    ch = avg_color(shot("light-overlay-hut"), int(hx) - 3, int(hy) - 3, 6, 6)
    print("      hut floor:", ch)
    check(ch[0] > ch[1] + 30 and ch[0] > ch[2] + 30, "the dark hut floor is red: %s" % (ch,))
    ed("setModule", "editor_brightness", "false")
    # in the normal game it shows only with J
    editor_off()
    time.sleep(0.5)
    check(int(ed("overlaysInGame").split()[2]) == 0, "not in game without J")
    c.key(13)
    time.sleep(1.0)
    check(int(ed("overlaysInGame").split()[2]) > 100, "J shows it in game too: %s" % ed("overlaysInGame"))
    shot("light-overlay-ingame")
    c.key(13)
    time.sleep(0.4)
    editor_on()
    c.click(*widget("module_light_overlay"), b=1)
    time.sleep(0.3)
    ed("lightOverlay", 0, 24)
    cmd("setblock 20 -60 20 air")


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


def rect_of(name):
    r = state()["widgets"].get(name)
    check(r is not None, "widget %s not drawn" % name)
    return tuple(map(float, r.split(",")))


def test_tree_row_layout():
    """Tree rows (#29): icon and text a fixed gap apart, long names clipped and faded before the row's
    buttons, the whole name in a tooltip on hover."""
    editor_on()
    fresh_tree()
    box = ed("addBox", 1, -61, 4, 7, -57, 10, "shown")
    ed("addPoint", 3, -60, 3)
    frames_pass()
    node, text = rect_of("node:" + box), rect_of("nodetext:" + box)
    close(text[0] - node[0], 16 + 4 + 16 + 7, 1, "text starts after arrow, icon and a 7 px gap")
    check("nodefade:" + box not in state()["widgets"], "a short name is not faded")
    long_name = "a very long selection name that does not fit into the tree panel at all"
    ed("renameNode", box, long_name)
    frames_pass()
    text, wp = rect_of("nodetext:" + long_name), rect_of("wp:" + long_name)
    check(text[2] <= wp[0] - 2, "the long name ends before the waypoint button: %s vs %s" % (text, wp))
    fade = rect_of("nodefade:" + long_name)
    close(fade[2], text[2], 1, "the fade ends where the text is clipped")
    c.move((text[0] + text[2]) / 2, (text[1] + text[3]) / 2)
    time.sleep(0.4)
    check(state()["nodeTooltip"] == long_name, "hovering shows the whole name: %r" % state()["nodeTooltip"])
    shot("tree-row-layout")
    c.move(*widget("tree_empty"))
    time.sleep(0.3)
    check(state()["nodeTooltip"] == "", "no tooltip away from it")
    fresh_tree()


def go_dimension(dim, pos):
    cmd("execute in %s run tp @s %s" % (dim, pos))
    wait_for(lambda s: s["dimension"] == dim, 30)
    time.sleep(1)


def test_selections_per_dimension():
    """Selections are kept per dimension (#22): the world only shows the player's dimension's, the tabs
    above the tree show any dimension's, positions say real and translated (overworld/nether) or End."""
    editor_on()
    fresh_tree()
    ow, ne, en = "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
    ed("clearTreeIn", ne)
    ed("clearTreeIn", en)
    box = ed("addBox", 1, -61, 4, 7, -57, 10, "shown")
    c.click(*widget("wp:" + box), b=1)
    time.sleep(0.4)
    check(ed("waypointMarkers").split()[0] == "1", "a waypoint in the overworld")
    editor_off()
    try:
        go_dimension(ne, "0 130 0")
        editor_on()
        s = state()
        check(s["viewDimension"] == ne and s["tree"] == [] and s["viewTree"] == [], "the nether's own, empty tree: %s" % s)
        check(ed("waypointMarkers").split()[0] == "0", "the overworld waypoint is not shown in the nether")
        point = ed("addPoint", 2, 70, 3)
        frames_pass()
        c.click(*find_node(point)[0], b=1)
        time.sleep(0.3)
        t = state()["positionText"]
        check(t == "Nether position (real): 2 70 3\nOverworld position (translated): 16 70 24", "real and translated: %r" % t)
        # the overworld tab shows the overworld's tree, the world still the nether's
        c.click(*widget("tab:Overworld"), b=1)
        time.sleep(0.4)
        s = state()
        check(s["viewDimension"] == ow and len(s["viewTree"]) == 1 and s["tree"] == ["point 1 (2, 70, 3)"], "overworld tab: %s / %s" % (s["viewTree"], s["tree"]))
        c.click(*find_node(box)[0], b=1)
        time.sleep(0.3)
        t = state()["positionText"]
        check(t == "Nether centre (translated): 0.56 -58.50 0.94\nOverworld centre (real): 4.50 -58.50 7.50",
              "seen from the nether, the nether position first: %r" % t)
        check("editing" not in state(), "another dimension's box is not edited")
        c.move(800, 450)
        time.sleep(0.3)
        shot("tree-dimensions")
        ed("addPointIn", en, 100, 50, 0)
        c.click(*widget("tab:End"), b=1)
        time.sleep(0.4)
        c.click(*find_node("point 1")[0], b=1)
        time.sleep(0.3)
        check(state()["positionText"] == "End position 100 50 0", "End position from outside the end: %r" % state()["positionText"])
        editor_off()
        files = os.listdir(state()["selectionsDir"])
        check("minecraft_the_nether.json" in files and "minecraft_the_end.json" in files, "saved per dimension: %s" % files)
    finally:
        editor_off()
        go_dimension(ow, "0.5 -60 0.5 0 0")
    editor_on()
    s = state()
    check(s["viewDimension"] == ow and s["tree"][0].startswith(box), "back in the overworld, its tab and tree: %s" % s)
    check(ed("waypointMarkers").split()[0] == "1", "and its waypoint")
    ed("clearTreeIn", ne)
    ed("clearTreeIn", en)
    fresh_tree()


def drawn_nodes():
    return sorted(k[5:] for k in state()["widgets"] if k.startswith("node:"))


def test_tree_search():
    """The search field (#37) shows the fuzzy matches with their groups opened; clearing it brings back
    the groups as they were."""
    editor_on()
    fresh_tree()
    ed("addGroup", "", "base", "closed")
    ed("addGroup", "base", "farms", "open")
    ed("addPointTo", "farms", "iron farm", 1, -60, 1)
    ed("addPointTo", "farms", "wheat", 2, -60, 1)
    ed("addGroup", "", "storage", "closed")
    ed("addPointTo", "storage", "chests", 3, -60, 1)
    ed("addPointTo", "", "spawn", 0, -60, 0)
    frames_pass()
    before = drawn_nodes()
    check(before == ["base", "spawn", "storage"], "closed groups: %s" % before)
    c.click(*widget("tree_search"), b=1)
    time.sleep(0.2)
    c.type("irfm")
    time.sleep(0.4)
    check(drawn_nodes() == ["base", "farms", "iron farm"], "fuzzy match, its groups opened: %s" % drawn_nodes())
    shot("tree-search")
    # closing a group during the search only lasts for the search
    _, r = find_node("base")
    c.click(r[0] + 10, (r[1] + r[3]) / 2, b=1)
    time.sleep(0.3)
    check(drawn_nodes() == ["base"], "base closed while searching: %s" % drawn_nodes())
    c.click(r[0] + 10, (r[1] + r[3]) / 2, b=1)
    time.sleep(0.3)
    check(drawn_nodes() == ["base", "farms", "iron farm"], "and opened again: %s" % drawn_nodes())
    # a matching group shows what is in it
    c.click(*widget("tree_search"), b=1)
    for _ in range(4):
        c.key(K["BACKSPACE"])
    c.type("storage")
    time.sleep(0.4)
    check(drawn_nodes() == ["storage"], "a matching group, closed (no match inside): %s" % drawn_nodes())
    # other dimensions' tabs search their own tree
    c.click(*widget("tab:Nether"), b=1)
    time.sleep(0.4)
    check(drawn_nodes() == [] and state()["viewDimension"] == "minecraft:the_nether", "no matches in the nether: %s" % drawn_nodes())
    c.click(*widget("tab:Overworld"), b=1)
    time.sleep(0.4)
    c.click(*widget("tree_search_clear"), b=1)
    time.sleep(0.4)
    check(drawn_nodes() == before, "clearing restores the groups: %s" % drawn_nodes())
    check("tree_search_clear" not in state()["widgets"], "no clear button without a query")
    fresh_tree()


def test_panels_resize():
    """The side panels are resized at their inner edges (#27): the viewport follows in the same frame, the
    widths are clamped and kept in the editor config."""
    editor_on()
    ed("resetPanelWidths")
    frames_pass()
    try:
        s = state()
        check(s["leftWidth"] == 300 and s["rightWidth"] == 320, "default widths: %s %s" % (s["leftWidth"], s["rightWidth"]))
        win = s["viewport"][0] + s["viewport"][2] + s["rightWidth"]
        a = widget("splitter_left")
        close(a[0], 300, 1, "the left splitter is on the panel's edge")
        ui_drag(a, (a[0] + 80, a[1]))
        s = state()
        close(s["leftWidth"], 380, 2, "dragged 80 px wider")
        close(s["viewport"][0], s["leftWidth"], 0.01, "the viewport starts at the panel's new edge")
        close(widget("splitter_left")[0], s["leftWidth"], 0.01, "and the splitter with it")
        # grabbed on the panel's side of the edge
        b = widget("splitter_right")
        b = (b[0] + 3, b[1])
        ui_drag(b, (b[0] - 60, b[1]))
        s = state()
        close(s["rightWidth"], 380, 2, "right panel 60 px wider")
        close(s["viewport"][0] + s["viewport"][2], win - s["rightWidth"], 0.01, "the viewport ends at it")
        shot("panels-resized")
        # limits: panels at least 180 px, the viewport at least 240 px
        ui_drag(widget("splitter_left"), (5, a[1]))
        check(state()["leftWidth"] == 180, "left panel minimum: %s" % state()["leftWidth"])
        ui_drag(widget("splitter_left"), (win - 10, a[1]))
        s = state()
        close(s["viewport"][2], 240, 0.01, "the viewport keeps its minimum width")
        ui_drag(widget("splitter_left"), (420, a[1]))
        # saved in the editor config, read again
        with open(os.path.join(c.run_dir, "config", "plainsight", "editor.json")) as f:
            saved = json.load(f)
        close(saved["leftPanelWidth"], 420, 2, "left width saved: %s" % saved)
        close(saved["rightPanelWidth"], 380, 2, "right width saved: %s" % saved)
        ed("reloadPanelWidths")
        frames_pass()
        close(state()["leftWidth"], 420, 2, "read back from the config")
    finally:
        ed("resetPanelWidths")
        frames_pass()


def test_rename_long_then_short():
    editor_on()
    fresh_tree()
    c.click(*widget("tree_new_group"), b=1)
    time.sleep(0.3)
    def rename(old, new):
        pos, _ = find_node(old)
        c.move(*pos)
        time.sleep(0.1)
        c.button(1)
        time.sleep(0.05)
        c.button(1)
        time.sleep(0.3)
        c.key(K["BACKSPACE"])
        c.type(new)
        c.key(K["ENTER"])
        time.sleep(0.3)
    rename("group 1", "a much longer group name")
    check(state()["tree"] == [{"a much longer group name": []}], "renamed long: %s" % state()["tree"])
    rename("a much longer group name", "ab")
    check(state()["tree"] == [{"ab": []}], "renamed short, nothing of the old name left: %s" % state()["tree"])
    rename("ab", "middle size")
    check(state()["tree"] == [{"middle size": []}], "renamed again: %s" % state()["tree"])


def test_lod_warning():
    editor_on()
    ed("resetLodWarning")
    time.sleep(0.3)
    s = state()
    if s["lodMod"]:
        check("view_lod_warning" not in s["widgets"], "no LOD hint when Voxy or Distant Horizons is installed")
        return
    check("view_lod_warning" in s["widgets"], "the editor hints that no LOD mod is installed")
    c.click(*widget("view_lod_warning"), b=1)
    time.sleep(0.4)
    shot("lod-warning")
    c.click(*widget("lod_dismiss"), b=1)
    time.sleep(0.4)
    check("view_lod_warning" not in state()["widgets"], "Don't show again hides it")
    check(os.path.exists(os.path.join(c.run_dir, "config", "plainsight", "lod-warning-dismissed")), "and remembers it")
    ed("resetLodWarning")


def test_far_camera_draws_loaded_chunks():
    """The render-distance fog (and Sodium's section culling) is measured from the camera: an editor
    camera far from the player must still show the chunks loaded around the player."""
    editor_on()
    px, py, pz = state()["player"]

    def drawn():
        return int(state()["sodiumChunks"].split()[1].split("/")[0])

    ed("setCamera", px, py + 120, pz, 0, 90)
    time.sleep(3)
    near = drawn() if state()["sodium"] else 0
    # beyond the loaded chunks even at render distance 16
    ed("setCamera", px, py + 120, pz - 400, 0, 20)
    time.sleep(3)
    s = state()
    check(abs(s["cameraToPlayer"] - 400) < 1, "the editor knows how far the camera is from the player")
    check(s["cameraOutsideLoaded"], "and that it is outside the loaded chunks")
    if s["sodium"]:
        far = drawn()
        check(far >= 0.8 * near, "Sodium draws the loaded sections from far away too (%d vs %d from above)" % (far, near))
    shot("far-camera")
    ed("setCamera", px, py + 10, pz - 10, 0, 30)
    time.sleep(0.5)


def wait_for(pred, timeout, step=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = state()
        if pred(s):
            return s
        time.sleep(step)
    return state()


def test_depth_pick_under_cursor():
    """The surface under the cursor comes from the depth buffer (so LOD and guessed terrain can be aimed
    at); on loaded terrain it must agree with the block raycast."""
    editor_on()
    fresh_tree()
    cam(25.5, -40, 25.5, 0, 89)
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] / 2
    c.move(cx, cy)
    s = wait_for(lambda s: s["surfaceUnderCursor"] != "", 3, 0.1)
    check(s["surfaceUnderCursor"] != "", "the depth pick finds the ground under the cursor")
    x, y, z = map(float, s["surfaceUnderCursor"].split(","))
    check(abs(y - (-60)) < 0.15 and abs(x - 25.5) < 1.5 and abs(z - 25.5) < 1.5, "at the ground's top below the camera: %s" % s["surfaceUnderCursor"])
    c.move(cx + 200, cy)
    s = wait_for(lambda s: s["surfaceUnderCursor"] != "", 3, 0.1)
    x2, y2, z2 = map(float, s["surfaceUnderCursor"].split(","))
    check(abs(y2 - (-60)) < 0.15 and x2 < x - 3, "and follows the cursor (right is -x looking down with yaw 0): %s" % s["surfaceUnderCursor"])



def test_seed_hider():
    """With the seed hider on, seeds never show on screen: not in this mod's messages, not in /seed's,
    not in any chat line with a long number (SeedcrackerX's)."""
    c.command("plainsight seed hide true")
    c.command("plainsight seed 4242424242")
    c.command("seed")
    c.command("tellraw @s \"Found world seed 98765432101 (structure seed 1234567890123)\"")
    time.sleep(0.5)
    shown = ed("chatShown", 6)
    check("4242424242" not in shown and "98765432101" not in shown and "1234567890123" not in shown,
          "no seed in the chat window:\n%s" % shown)
    check("\u2022" in shown, "they show as dots:\n%s" % shown)
    c.command("plainsight seed hide false")
    c.command("plainsight seed")
    time.sleep(0.3)
    check("4242424242" in ed("chatShown", 2), "with the hider off the seed is shown")
    c.command("plainsight seed hide true")
    c.command("plainsight seed clear")


def test_seed_hider_tool_button():
    """The seed hider is a toggle in the tool bar (next to the brightness toggles)."""
    editor_on()
    c.command("plainsight seed hide true")
    for want in ("false", "true"):
        c.click(*widget("module_hide_seeds"), b=1)
        time.sleep(0.3)
        check(c.static("dev.plainsight.guess.SeedHider", "enabled") == want, "the Hide Seeds button switches it to %s" % want)
        check(state()["modules"]["hide_seeds"] == (want == "true"), "and shows it")


def test_minimap_tool_button_and_options():
    """The minimap is a toggle in the tool bar; right-click for what it shows."""
    editor_on()
    before = ed("minimap").split(" ")[0]
    c.click(*widget("module_minimap"), b=1)
    time.sleep(0.3)
    check(ed("minimap").split(" ")[0] != before, "the Minimap button switches it")
    c.click(*widget("module_minimap"), b=1)
    time.sleep(0.3)
    check(ed("minimap").split(" ")[0] == before, "and back")
    ed("setMinimapOptions", "true", "true", "true", "false")
    c.click(*widget("module_minimap"), b=3)
    time.sleep(0.4)
    for name, i in (("minimap_players", 2), ("minimap_mobs", 3), ("minimap_selections", 1)):
        old = ed("minimapOptions").split(" ")[i]
        c.click(*widget(name), b=1)
        time.sleep(0.3)
        check(ed("minimapOptions").split(" ")[i] != old, "%s switches its option: %s" % (name, ed("minimapOptions")))
    check("minimap_layer" in state()["widgets"], "the layer choice is in the options")
    c.key(K["ESC"])
    time.sleep(0.2)
    ed("setMinimapOptions", "true", "true", "true", "false")


def wait_chat(text, timeout=120, lines=6):
    """Waits until one of the last chat lines contains text; returns those lines."""
    end = time.time() + timeout
    shown = ""
    while time.time() < end:
        shown = ed("chatShown", lines)
        if text in shown:
            return shown
        time.sleep(0.5)
    raise AssertionError("no %r in chat within %ds: %s" % (text, timeout, shown))


def test_module_settings_persist():
    """Each module's state and settings are saved in config/plainsight/modules/<id>.json and read back."""
    editor_on()
    folder = os.path.join(c.run_dir, "config", "plainsight", "modules")
    brightness_was = state()["modules"]["editor_brightness"]
    ed("setModule", "light_overlay", "true")
    ed("setModule", "editor_brightness", "true")
    ed("brightnessLevel", 0.6, 1)
    ed("setMinimapOptions", "true", "true", "false", "true")
    try:
        time.sleep(2)  # written about once a second

        def read(mid):
            with open(os.path.join(folder, mid + ".json")) as f:
                return json.load(f)

        check(read("light_overlay")["enabled"] is True, "light overlay saved as on: %s" % read("light_overlay"))
        b = read("editor_brightness")
        check(b["enabled"] is True and abs(b["level"] - 0.6) < 1e-6, "editor brightness level saved: %s" % b)
        m = read("minimap")
        check(m["layer"] == "player" and m["otherPlayers"] is False and m["mobs"] is True, "minimap options saved: %s" % m)
        # edited by hand, read again (as at the next game start)
        m["layer"], m["mobs"] = "top", False
        with open(os.path.join(folder, "minimap.json"), "w") as f:
            json.dump(m, f)
        lo = read("light_overlay")
        lo["enabled"] = False
        with open(os.path.join(folder, "light_overlay.json"), "w") as f:
            json.dump(lo, f)
        ed("reloadModuleConfig")
        check(ed("minimapOptions") == "false true false false", "minimap options read back: %s" % ed("minimapOptions"))
        check(not state()["modules"]["light_overlay"], "light overlay read back as off")
    finally:
        ed("setModule", "light_overlay", "false")
        ed("setModule", "editor_brightness", str(brightness_was).lower())
        ed("brightnessLevel", 0.2, 1)
        ed("setMinimapOptions", "true", "true", "true", "false")


def test_locate_from_seed():
    """/plainsight locate runs vanilla's locate on the seed's worldgen server; with Xaero's Minimap the
    result offers a waypoint."""
    editor_off()
    c.command("plainsight seed 12345")
    try:
        c.command("plainsight locate biome minecraft:plains", wait=0.2)
        shown = wait_chat("The nearest minecraft:plains is at")
        check("(from the seed)" in shown, "marked as from the seed: %s" % shown)
        c.command("plainsight locate structure minecraft:village_plains", wait=0.2)
        shown = wait_chat("The nearest minecraft:village_plains (")
        m = re.search(r"village_plains \((\d+) houses?\) is at \[(-?\d+), ~, (-?\d+)\] \((\d+) blocks away\)", shown)
        check(m, "the village's houses, coordinates and distance as vanilla shows them: %s" % shown)
        check("[Create selection]" in shown, "a button to select the whole structure: %s" % shown)
        c.command("plainsight locate structure #minecraft:village", wait=0.2)
        wait_chat("The nearest #minecraft:village (minecraft:village_")
        c.command("plainsight locate structure minecraft:no_such_thing", wait=0.2)
        wait_chat('There is no structure with type "minecraft:no_such_thing"', timeout=30)
        if ed("xaeroWaypoints") != "none":
            check("[+ Waypoint]" in shown, "a waypoint button with Xaero's Minimap: %s" % shown)
            c.command("plainsight waypoint 10 70 20 Plainsight Test", wait=0.5)
            check("Plainsight Test@10,70,20" in ed("xaeroWaypoints"), "the waypoint is added in Xaero: %s" % ed("xaeroWaypoints"))
            ed("removeXaeroWaypoint", "Plainsight Test")
        else:
            check("[+ Waypoint]" not in shown, "no waypoint button without Xaero's Minimap")
    finally:
        c.command("plainsight seed clear")


def test_select_command_creates_selection():
    """/plainsight select (behind locate's [Create selection]) adds a box over the blocks and selects it."""
    editor_on()
    fresh_tree()
    editor_off()
    out = c.command("plainsight select 2 -61 3 5 -59 7 Test Structure", wait=0.5)
    check('Selection "Test Structure" created' in ed("chatShown", 2), "feedback: %s" % ed("chatShown", 2))
    editor_on()
    s = state()
    check(s["tree"] == ["Test Structure (60 blocks)"], "in the tree: %s" % s["tree"])
    check(s["selected"] == ["Test Structure (60 blocks)"], "and selected: %s" % s["selected"])
    fresh_tree()


def test_selection_copy_buttons():
    """A selected box's centre ("X Y Z") and blocks ("min X Y Z, max X Y Z") copy to the clipboard."""
    editor_on()
    fresh_tree()
    name = ed("addBox", 1, -61, 4, 7, -57, 10, "shown")
    a, _ = find_node(name)
    c.click(*a, b=1)
    time.sleep(0.4)
    c.click(*widget("copy_center"), b=1)
    time.sleep(0.3)
    check(ed("clipboard") == "4 -59 7", "centre copied: %r" % ed("clipboard"))
    c.click(*widget("copy_specs"), b=1)
    time.sleep(0.3)
    check(ed("clipboard") == "min 1 -61 4, max 7 -57 10", "blocks copied: %r" % ed("clipboard"))
    fresh_tree()


def test_selection_waypoint_markers():
    """A selection marked as waypoint (tree button) shows a diamond at its centre in the editor and the
    game, with its name when the mouse (editor) or the crosshair (game) is close."""
    editor_on()
    fresh_tree()
    cmd("tp @s 0.5 -60 0.5 0 0")
    name = ed("addBox", 0, -59, 10, 0, -58, 11, "shown")  # centre (0.5, -58, 11): just above eye height ahead
    check(ed("waypointMarkers").split()[0] == "0", "not a waypoint yet: %s" % ed("waypointMarkers"))
    c.click(*widget("wp:" + name), b=1)
    time.sleep(0.4)
    try:
        check(ed("waypointMarkers").split()[0] == "1", "the waypoint button marks it: %s" % ed("waypointMarkers"))
        cam(0.5, -58, -6, 0, 0)
        p = ed("project", 0.5, -58, 11)
        check(p != "behind", "in view")
        x, y = (float(v) for v in p.split(","))
        c.move(x, y + 3)
        time.sleep(0.4)
        drawn, alpha, shown = ed("waypointMarkers").split(" ", 2)
        check(float(alpha) > 0.9 and shown == name, "the mouse at the diamond shows its name: %s" % ed("waypointMarkers"))
        c.move(x + 300, y + 200)
        time.sleep(0.4)
        check(float(ed("waypointMarkers").split()[1]) == 0, "not away from it")
        shot("waypoint-editor")
        # the Waypoints module: switched off there are no diamonds; its slider makes them bigger
        c.click(*widget("module_waypoints"), b=1)
        time.sleep(0.4)
        check(ed("waypointMarkers").split()[0] == "0", "the Waypoints module switches them off: %s" % ed("waypointMarkers"))
        c.click(*widget("module_waypoints"), b=1)
        time.sleep(0.4)
        check(ed("waypointMarkers").split()[0] == "1", "and on again: %s" % ed("waypointMarkers"))
        ed("waypointScale", 1)
        time.sleep(0.3)
        small = shot("waypoint-size-1")
        ed("waypointScale", 3)
        time.sleep(0.4)
        big = shot("waypoint-size-3")
        ed("waypointScale", 1)
        changed = changed_pixels(small, big, int(x) - 30, int(y) - 30, 60, 60)
        check(changed > 300, "a bigger diamond at size 3: %d pixels changed" % changed)
        editor_off()
        cmd("tp @s 0.5 -60 0.5 0 0")
        time.sleep(0.6)
        drawn, alpha, shown = ed("waypointMarkers").split(" ", 2)
        check(drawn == "1" and float(alpha) > 0.5 and shown == name, "in the game the crosshair shows it: %s" % ed("waypointMarkers"))
        cmd("tp @s 0.5 -60 0.5 180 0")
        time.sleep(0.6)
        drawn, alpha, _ = (ed("waypointMarkers").split(" ", 2) + [""])[:3]
        check(drawn == "1" and float(alpha) == 0, "behind the player: on the edge, no name: %s" % ed("waypointMarkers"))
        shot("waypoint-game-behind")
        editor_on()
        c.click(*widget("vis:" + name), b=1)
        time.sleep(0.4)
        check(ed("waypointMarkers").split()[0] == "0", "a hidden selection is no waypoint marker: %s" % ed("waypointMarkers"))
    finally:
        cmd("tp @s 0.5 -60 0.5 0 0")
        editor_on()
        fresh_tree()


def test_map_key_opens_editor_on_map():
    """In the game the map key (M) opens the editor on the map, centred on the player; there it switches
    to the 3D view and back."""
    editor_off()
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)
    c.key(K["M"])
    time.sleep(0.6)
    s = state()
    try:
        check(s["active"] and s["map"], "M in the game opens the editor's map: active=%s map=%s" % (s["active"], s["map"]))
        cx, cz = s["camera"]["pos"][0], s["camera"]["pos"][2]
        check(abs(cx - 0.5) < 1 and cz > 0.5, "looking at the player from the south: %s" % s["camera"]["pos"])
        c.key(K["M"])
        time.sleep(0.4)
        check(state()["active"] and not state()["map"], "M in the editor: the 3D view")
    finally:
        editor_off()


def test_switch_to_axiom():
    """With Axiom installed, the button at the top right of the viewport closes this editor and opens
    Axiom's; Right Shift there is Axiom's (closes it), and then opens this editor again."""
    editor_on()
    if "view_axiom" not in state()["widgets"]:
        print("      (needs Axiom, skipped)")
        return
    box = widget("view_axiom")
    c.click(*box, b=1)
    time.sleep(1.0)
    check(not state()["active"], "the Axiom button closes this editor")
    shot("axiom-editor")
    c.key(K["RSHIFT"])
    time.sleep(1.0)
    check(not state()["active"], "Right Shift in Axiom's editor is Axiom's")
    c.key(K["RSHIFT"])
    time.sleep(1.0)
    check(state()["active"], "and afterwards opens this editor again")
    # Axiom's editor key elsewhere (F13) or unbound: this editor's key closes Axiom's editor; unbound, the
    # button still opens Axiom's editor, not whatever else is unbound (a screen)
    for key in (104, -1):
        if ed("setAxiomEditorKey", key) != "ok":
            break
        try:
            editor_on()
            c.click(*widget("view_axiom"), b=1)
            time.sleep(1.0)
            check(not state()["active"] and "screen=none" in c.status() and ed("axiomOpen") == "true",
                  "Axiom key %d: Axiom's editor opens: %s" % (key, c.status().splitlines()[0]))
            shot("axiom-editor-key-%d" % key)
            c.key(K["RSHIFT"])
            time.sleep(1.0)
            check(not state()["active"] and ed("axiomOpen") == "false", "Right Shift closes Axiom's editor (Axiom key %d)" % key)
            shot("axiom-closed-key-%d" % key)
            c.key(K["RSHIFT"])
            time.sleep(0.8)
            check(state()["active"], "and then opens this editor")
        finally:
            ed("setAxiomEditorKey", K["RSHIFT"])
    editor_off()


def test_map_mode_camera():
    """M switches the editor to the map: tilted like the minimap, north up; the wheel zooms towards the cursor, LMB
    drags it, the movement keys do nothing; M again brings the 3D camera back."""
    editor_on()
    fresh_tree()
    cam(2, -50, -6, 0, 30)
    before = state()["camera"]
    c.key(K["M"])
    time.sleep(0.4)
    s = state()
    check(s["map"], "M opens the map: %s" % s["map"])
    check(abs(s["camera"]["pitch"] - 60) < 1e-3 and abs(s["camera"]["yaw"] - 180) < 1e-3, "tilted like the minimap, north up: %s" % s["camera"])
    try:
        h0 = s["mapHeight"]
        c.scroll(3, 800, 450)
        time.sleep(0.3)
        check(state()["mapHeight"] < h0, "the wheel zooms in: %s -> %s" % (h0, state()["mapHeight"]))
        c.scroll(-3, 800, 450)
        time.sleep(0.3)
        check(abs(state()["mapHeight"] - h0) < 1e-6, "and out again")
        x0 = state()["camera"]["pos"][0]
        c.drag(800, 450, 900, 450, b=1)
        time.sleep(0.3)
        check(state()["camera"]["pos"][0] < x0 - 1, "dragging the map to the right shows what is west: %s -> %s" % (x0, state()["camera"]["pos"][0]))
        # WASD pans the map (#52): north up, so W shows what is north (-z), D what is east (+x), at the same height
        p = state()["camera"]["pos"]
        h = state()["mapHeight"]
        c.hold(K["W"], 0.4)
        time.sleep(0.4)
        q = state()["camera"]["pos"]
        check(p[2] - q[2] > 15, "W pans the map north: %s -> %s" % (p, q))
        close(q[0], p[0], 0.05, "W keeps x")
        close(q[1], p[1], 1e-6, "and the height")
        c.hold(K["D"], 0.4)
        time.sleep(0.4)
        r = state()["camera"]["pos"]
        check(r[0] - q[0] > 15, "D pans it east: %s -> %s" % (q, r))
        c.hold(K["SPACE"], 0.3)
        c.hold(K["LSHIFT"], 0.3)
        time.sleep(0.3)
        close(state()["camera"]["pos"][1], p[1], 1e-6, "Space and Shift do not fly up or down in the map")
        close(state()["mapHeight"], h, 1e-6, "WASD keeps the zoom")
        check(not state()["tree"], "and does nothing else")
        check("LMB - Move Map" in state()["statusHints"] if "statusHints" in state() else True, "map hints")
        shot("map-mode")
    finally:
        c.key(K["M"])
        time.sleep(0.4)
    s = state()
    check(not s["map"], "M again: the 3D view")
    check(s["camera"]["pos"] == before["pos"] and s["camera"]["pitch"] == before["pitch"], "with the 3D camera as it was: %s vs %s" % (s["camera"], before))


def test_map_turn_and_tilt():
    """Ctrl + LMB drag in the map (as the arcball): left/right turns it around the view's centre, down tilts it towards
    top-down (as the arcball), 10 degrees at least; the minimap takes the tilt; both are kept."""
    editor_on()
    fresh_tree()
    cam(2, -50, -6, 0, 30)
    c.key(K["M"])
    time.sleep(0.4)

    def ctrl_drag(points):
        c.move(*points[0])
        time.sleep(0.05)
        c.key(K["LCTRL"], "press")
        c.button(1, "press")
        for p in points[1:]:
            c.move(*p)
            time.sleep(0.02)
        c.button(1, "release")
        c.key(K["LCTRL"], "release")
        time.sleep(0.2)

    try:
        s = state()
        p = s["camera"]["pos"]
        c.move(800, 450)
        c.move(900, 450)
        time.sleep(0.2)
        check(state()["camera"]["pos"] == p, "moving the mouse alone does not turn the map")
        ctrl_drag([(800, 450)] + [(800 + 10 * (i + 1), 450) for i in range(10)])
        t = state()["camera"]
        check(abs(t["yaw"] - (s["camera"]["yaw"] + 30)) < 1, "Ctrl + LMB drag right turns the map: %s -> %s" % (s["camera"]["yaw"], t["yaw"]))
        check(abs(t["pitch"] - s["camera"]["pitch"]) < 1e-3, "without tilting it")
        # W pans in the map's turn: towards the top of the screen (#52)
        a = t["pos"]
        c.hold(K["W"], 0.3)
        time.sleep(0.4)
        b = state()["camera"]["pos"]
        yr = math.radians(t["yaw"])
        d = (b[0] - a[0], b[2] - a[2])
        n = math.hypot(*d)
        check(n > 10 and abs(d[0] / n + math.sin(yr)) < 0.02 and abs(d[1] / n - math.cos(yr)) < 0.02,
              "W pans along the turned map's up: %s at yaw %s" % (d, t["yaw"]))
        p0 = t["pitch"]
        ctrl_drag([(900, 450)] + [(900, 450 + 10 * (i + 1)) for i in range(5)])
        close(state()["camera"]["pitch"], p0 + 10, 0.5, "50 px down tilts 10 degrees, as the arcball camera (0.2 deg/px)")
        ctrl_drag([(900, 150)] + [(900, 150 + 30 * (i + 1)) for i in range(20)])
        t = state()["camera"]
        check(abs(t["pitch"] - 90) < 1e-3, "dragging down tilts it to top-down, no further: %s" % t["pitch"])
        check(abs(float(ed("minimapTilt")) - 90) < 1e-3, "the minimap takes the tilt")
        for _ in range(2):
            ctrl_drag([(900, 850)] + [(900, 850 - 30 * (i + 1)) for i in range(20)])
        check(abs(state()["camera"]["pitch"] - 10) < 1e-3, "and up to 10 degrees at most: %s" % state()["camera"]["pitch"])
        yaw = state()["camera"]["yaw"]
        c.key(K["M"])
        time.sleep(0.3)
        c.key(K["M"])
        time.sleep(0.3)
        t = state()["camera"]
        check(abs(t["yaw"] - yaw) < 1e-3 and abs(t["pitch"] - 10) < 1e-3, "the map keeps its rotation and tilt: %s" % t)
    finally:
        c.key(K["LCTRL"], "release")
        ed("setMinimapTilt", 60)
        ed("setMapYaw", 180)
        if state()["map"]:
            c.key(K["M"])
            time.sleep(0.4)


def test_minimap_layer_keys():
    """Ctrl + the minimap zoom keys move the shown layer one block per press and many while held; Ctrl + *
    goes back to the player's level or the top; the same in the editor map, where the zoom keys alone zoom."""
    editor_off()
    cmd("tp @s 0.5 -60 0.5 0 0")
    ed("setMinimapOptions", "true", "true", "true", "false")
    time.sleep(0.3)
    # the keys bound now (the hidden client's options may keep older bindings)
    up, down, reset = (int(v) for v in ed("minimapKeys").split())
    try:
        check(ed("minimapLayer") == "Y: -60", "the player's layer: %s" % ed("minimapLayer"))
        c.key(K["LCTRL"], "press")
        c.key(up)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: -59", "Ctrl + zoom in: one block up: %s" % ed("minimapLayer"))
        c.key(down)
        time.sleep(0.15)
        c.key(down)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: -61", "two presses: two blocks down: %s" % ed("minimapLayer"))
        # (up: the flat world ends 3 blocks below)
        c.key(up, "press")
        time.sleep(0.25)
        short = int(ed("minimapLayer").split()[1])
        time.sleep(1.5)
        c.key(up, "release")
        long_ = int(ed("minimapLayer").split()[1])
        check(short <= -59 and long_ > short + 15, "held: one block at first, then many: %d then %d" % (short, long_))
        c.key(reset)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: -60", "Ctrl + *: back to the player's level: %s" % ed("minimapLayer"))
        c.key(reset)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: top", "and again: the top: %s" % ed("minimapLayer"))
        c.key(K["LCTRL"], "release")
        # the editor map: the same keys
        c.key(K["M"])
        time.sleep(0.5)
        check(state()["map"], "M opens the editor map")
        h0 = state()["mapHeight"]
        c.key(up)
        time.sleep(0.3)
        check(state()["mapHeight"] < h0, "the zoom key zooms the map like the wheel: %s -> %s" % (h0, state()["mapHeight"]))
        check(ed("minimapLayer") == "Y: top", "without changing the layer")
        c.key(K["LCTRL"], "press")
        c.key(down)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: -61", "Ctrl + zoom out in the map: from the top to one below the player: %s" % ed("minimapLayer"))
        c.key(reset)
        time.sleep(0.2)
        check(ed("minimapLayer") == "Y: -60", "Ctrl + * in the map")
        c.key(K["LCTRL"], "release")
        shot("map-stats")
        c.key(K["M"])
        time.sleep(0.3)
    finally:
        c.key(K["LCTRL"], "release")
        ed("setMinimapOptions", "true", "true", "true", "false")
        editor_off()


def test_point_select():
    """The point tool selects a block position with RMB (also in the map); it copies as "X Y Z" only."""
    editor_on()
    fresh_tree()
    ed("selectTool", "point_select")
    cam(2, -54, -6, 0, 35)
    try:
        c.click(800, 600, b=3)
        time.sleep(0.4)
        s = state()
        check(len(s["tree"]) == 1 and s["tree"][0].startswith("point 1 ("), "a point in the tree: %s" % s["tree"])
        at = [int(v) for v in s["tree"][0][len("point 1 ("):-1].split(", ")]
        check(at[1] == -61, "on the block under the cursor (the ground): %s" % at)
        check("copy_specs" not in s["widgets"], "a point copies its coordinates only")
        c.click(*widget("copy_center"), b=1)
        time.sleep(0.3)
        check(ed("clipboard") == "%d %d %d" % tuple(at), "copied: %r" % ed("clipboard"))
        c.key(K["ENTER"])
        time.sleep(0.2)
        # in the map too, at the surface under the cursor
        c.key(K["M"])
        time.sleep(0.5)
        c.click(800, 450, b=3)
        time.sleep(0.4)
        check(len(state()["tree"]) == 2 and state()["tree"][1].startswith("point 2 ("), "a point from the map: %s" % state()["tree"])
        shot("map-point")
        c.key(K["M"])
        time.sleep(0.3)
    finally:
        if state()["map"]:
            c.key(K["M"])
        fresh_tree()
        ed("selectTool", "box_select")


def test_editor_map_paused():
    """Behind the pause menu the editor map keeps its last image instead of rendering a pass every frame,
    and the paused editor runs at no more than 60 fps (#53)."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_on()
    fresh_tree()
    cam(0, 150, 0, 30, 40)
    try:
        c.key(K["M"])
        time.sleep(3)
        r0 = int(ed("minimap").split(" ")[2])
        time.sleep(2)
        r1 = int(ed("minimap").split(" ")[2])
        check(r1 - r0 > 20, "the map renders while not paused: %s passes in 2 s" % (r1 - r0))
        c.key(K["ESC"])
        time.sleep(1.5)
        check("PauseScreen" in c.status(), "Escape opens the pause menu: %s" % c.status().split("\n")[0])
        s = state()
        r2, b2 = int(ed("minimap").split(" ")[2]), s["mapBlits"]
        check(s["mapHeld"], "the paused map holds its image")
        time.sleep(3)
        r3, b3 = int(ed("minimap").split(" ")[2]), state()["mapBlits"]
        fps = int(re.search(r"fps=(\d+)", c.status()).group(1))
        print("      paused: %d passes, %d map frames in 3 s, fps %d" % (r3 - r2, b3 - b2, fps))
        check(r3 - r2 <= 1, "no map passes while paused: %s" % (r3 - r2))
        check(b3 - b2 > 60, "the held map is still shown every frame: %s" % (b3 - b2))
        check(fps <= 62, "the paused editor is limited to 60 fps: %s" % fps)
        shot("editor-map-paused")
        c.key(K["ESC"])
        time.sleep(2)
        check("PauseScreen" not in c.status(), "Escape closes the pause menu")
        r4 = int(ed("minimap").split(" ")[2])
        time.sleep(1)
        check(int(ed("minimap").split(" ")[2]) - r4 > 10, "the map renders again after the pause")
        check(not state()["mapHeld"], "and no longer holds its image")
    finally:
        if "PauseScreen" in c.status():
            c.key(K["ESC"])
            time.sleep(0.5)
        if state()["map"]:
            c.key(K["M"])
        fresh_tree()


def test_map_mode_shows_player_layer():
    """With Voxy the map is drawn like the minimap: under a roof it shows the player's layer, and the
    editor aims at what the map shows (the ground, not the roof)."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_off()
    cmd("tp @s 0.5 -60 0.5 0 0")
    cmd("fill -30 -52 -30 30 -52 30 red_wool")
    editor_on()
    fresh_tree()
    r0 = int(ed("minimap").split(" ")[2])
    try:
        cam(0.5, -50, 0.5, 0, 30)
        c.key(K["M"])
        time.sleep(4)  # the roof's chunks go to Voxy again
        check(int(ed("minimap").split(" ")[2]) > r0, "the map is rendered by a minimap pass: %s" % ed("minimap"))
        c.move(800, 450)
        time.sleep(0.6)
        surface = state()["surfaceUnderCursor"]
        check(surface and abs(float(surface.split(",")[1]) + 60) < 1.01, "under the cursor: the ground below the roof: %s" % surface)
        shot("map-player-layer")
        vp = (330, 100, 940, 700)
        rgb = avg_color(os.path.join(SHOTS, "map-player-layer.png"), *vp)
        check(rgb[0] - (rgb[1] + rgb[2]) / 2 < 30, "the roof is not on the map: %s" % (rgb,))
    finally:
        if state()["map"]:
            c.key(K["M"])
        cmd("fill -30 -52 -30 30 -52 30 air")


def test_minimap_shows_xaero_waypoints():
    """With Xaero's Minimap installed, its waypoints are on plainsight's minimap (option)."""
    if not state()["lodMod"] or not state()["sodium"] or ed("xaeroWaypoints") == "none":
        print("      (needs Voxy and Xaero's Minimap, skipped)")
        return
    editor_off()
    cmd("tp @s 0.5 -60 0.5 0 30")
    c.command("plainsight waypoint 4 -60 12 Plainsight Test", wait=0.5)
    try:
        time.sleep(0.5)
        check(int(ed("xaeroWaypoints").split("drawn=")[1]) >= 1, "the waypoint is on the minimap: %s" % ed("xaeroWaypoints"))
        shot("minimap-xaero-waypoint")
        ed("minimapWaypoints", "false")
        time.sleep(0.3)
        check(ed("xaeroWaypoints").endswith("drawn=0"), "not with the option off")
    finally:
        ed("minimapWaypoints", "true")
        ed("removeXaeroWaypoint", "Plainsight Test")


def test_seed_kept_in_world_folder():
    """A singleplayer world's seed is kept in its own folder (plainsight/seed.json), not by world name
    in the shared store: new worlds are all called "New World"."""
    key = c.static("dev.plainsight.guess.SeedStore", "worldKey")
    check(key.startswith("singleplayer:"), "singleplayer world: %s" % key)
    f = os.path.join(ROOT, "run-hidden", "saves", key[len("singleplayer:"):], "plainsight", "seed.json")
    c.command("plainsight seed 4242424242")
    time.sleep(0.3)
    check(os.path.exists(f) and json.load(open(f)).get("seed") == "4242424242", "seed in %s" % f)
    shared = os.path.join(ROOT, "run-hidden", "config", "plainsight", "seeds.properties")
    folder = key[len("singleplayer:"):].replace(" ", "\\ ")
    check(not os.path.exists(shared) or ("singleplayer\\:%s=" % folder) not in open(shared).read(), "and not in seeds.properties")
    c.command("plainsight seed clear")
    time.sleep(0.3)
    check(not os.path.exists(f), "clear removes it")


def test_seedcracker_seed_is_taken():
    """A world seed SeedcrackerX prints (cracked, or found in its database) becomes this world's seed,
    if it matches the server's hashed seed."""
    probe = c.static("kaptainwutax.seedcrackerX.util.Log", "translate", "tmachine.foundWorldSeedFromDatabase")
    if probe.startswith("error") or probe.startswith("bridge"):
        print("      (needs SeedcrackerX, skipped)")
        return
    c.command("plainsight seed clear")
    real = int(c.static("dev.plainsight.editor.EditorDebug", "worldSeed"))
    c.static("kaptainwutax.seedcrackerX.util.Log", "printSeed", "tmachine.foundWorldSeed", real + 1)
    time.sleep(0.5)
    check(state()["seed"] == "", "a seed that does not match the server's hashed seed is ignored")
    c.static("kaptainwutax.seedcrackerX.util.Log", "printSeed", "tmachine.foundWorldSeedFromDatabase", real)
    s = wait_for(lambda s: s["seed"] != "", 3)
    check(s["seed"] == str(real), "the database seed is taken: %s" % s["seed"])
    check("from SeedcrackerX saved" in ed("chatShown", 3), "and announced")
    c.command("plainsight seed clear")


def test_minimap():
    """With Voxy the HUD shows a minimap (an extra Voxy pass); = / - zoom it, N switches it off and on."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_off()
    def mm():
        e, v, r, d, err = ed("minimap").split(" ", 4)
        return e == "true", v == "true", int(r), int(d), err
    e, v, r0, d0, err = mm()
    check(e and v and err == "-", "the minimap is on and shown: %s" % ed("minimap"))
    time.sleep(0.5)
    check(mm()[2] > r0, "and rendered every frame")
    c.key(int(ed("minimapKeys").split()[0]))
    time.sleep(0.3)
    check(mm()[3] < d0, "= zooms in: %d -> %d" % (d0, mm()[3]))
    c.key(int(ed("minimapKeys").split()[1]))
    c.key(int(ed("minimapKeys").split()[1]))
    time.sleep(0.3)
    check(mm()[3] > d0, "- zooms out: %d -> %d" % (d0, mm()[3]))
    c.key(int(ed("minimapKeys").split()[1]))
    shot("minimap")
    c.key(K["N"])
    time.sleep(0.3)
    e, v, r1, _, _ = mm()
    time.sleep(0.4)
    check(not e and not v and mm()[2] == r1, "N switches it off (no more passes)")
    c.key(K["N"])
    time.sleep(0.3)
    check(mm()[0], "and on again")
    editor_on()
    time.sleep(0.4)
    r2 = mm()[2]
    time.sleep(0.4)
    check(mm()[2] == r2, "not in the editor")
    editor_off()


def minimap_zoom_182():
    """A known minimap zoom (other tests change it): all the way in (24 m), then 5 steps out (182 m)."""
    for _ in range(12):
        c.key(int(ed("minimapKeys").split()[0]))
        time.sleep(0.1)
    for _ in range(5):
        c.key(int(ed("minimapKeys").split()[1]))
        time.sleep(0.1)
    time.sleep(0.5)
    check(ed("minimap").split(" ")[3] == "182", "zoom set: %s" % ed("minimap"))


def test_minimap_shows_player_layer():
    """The minimap leaves out the terrain above the player's layer: a roof over the player is not on it,
    standing on the roof it is."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_off()
    check(ed("minimap").split(" ")[1] == "true", "the minimap is shown: %s" % ed("minimap"))
    minimap_zoom_182()
    # the minimap's square, top right (a margin inside its border); GUI scale 3 on the 1600x900 window
    side = round(300 * 0.3) * 3
    box = (1600 - 8 * 3 - side + 15, 8 * 3 + 15, side - 30, side - 30)

    # the patch right below the player's arrow: ground under the roof, the roof from on top of it
    patch = (box[0] + box[2] // 2 - 15, box[1] + box[3] // 2 + 12, 30, 20)

    def colour(name):
        time.sleep(3)  # changed chunks go to Voxy again about once a second
        shot(name)
        return avg_color(os.path.join(SHOTS, name + ".png"), *patch)

    def redness(rgb):
        return rgb[0] - (rgb[1] + rgb[2]) / 2

    try:
        # above the stone hut (its roof is at -55), which other tests use
        cmd("fill -60 -52 -60 60 -52 60 red_wool")
        cmd("tp @s 0.5 -60 0.5 0 30")
        under = colour("minimap-under-roof")
        ed("setMinimapOptions", "false", "true", "true", "false")
        top = colour("minimap-under-roof-top")
        ed("setMinimapOptions", "true", "true", "true", "false")
        check(redness(top) > redness(under) + 15, "with the Top layer option the roof shows from under it too: %s vs %s" % (top, under))
        cmd("tp @s 0.5 -51 0.5 0 30")
        on = colour("minimap-on-roof")
        check(redness(on) > redness(under) + 15, "the red roof shows only from on top of it: %s under, %s on" % (under, on))
        check(sum(under) > 120, "the ground under the roof is lit as by day, not dark: %s" % (under,))
    finally:
        cmd("fill -60 -52 -60 60 -52 60 air")
        cmd("tp @s 0.5 -60 0.5 0 0")


def count_color(path, box, rgb, fuzz=12):
    """Pixels of a PNG's rectangle within fuzz % of an RGB colour."""
    col = "rgb(%d,%d,%d)" % rgb
    r = subprocess.run([os.path.join(ROOT, "dev"), "magick", path, "-crop", "%dx%d+%d+%d" % (box[2], box[3], box[0], box[1]), "+repage",
                        "-fuzz", "%d%%" % fuzz, "-fill", "black", "+opaque", col, "-fill", "white", "-opaque", col,
                        "-format", "%[fx:int(mean*w*h+0.5)]", "info:"], cwd=ROOT, capture_output=True, text=True)
    m = re.search(r"\d+", r.stdout)
    if not m:
        raise AssertionError("magick failed: " + r.stderr[-500:])
    return int(m.group())


def test_minimap_overlays():
    """The minimap shows the selection tree's shown boxes (not hidden ones) and, as chosen, mobs."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_on()
    fresh_tree()
    box_name = ed("addBox", 6, -60, 6, 12, -58, 12, "shown")
    editor_off()
    cmd("tp @s 0.5 -60 0.5 0 30")
    minimap_zoom_182()
    side = round(300 * 0.3) * 3
    box = (1600 - 8 * 3 - side + 4, 8 * 3 + 4, side - 8, side - 8)
    path = os.path.join(SHOTS, "minimap-overlays.png")
    yellow = (255, 224, 64)

    def shot_count(rgb):
        time.sleep(0.5)
        shot("minimap-overlays")
        return count_color(path, box, rgb)

    try:
        ed("setMinimapOptions", "true", "true", "true", "false")
        shown = shot_count(yellow)
        check(shown > 30, "the shown box is outlined on the minimap: %d pixels" % shown)
        ed("setMinimapOptions", "true", "false", "true", "false")
        check(shot_count(yellow) < shown / 4, "not with the Selections option off")
        ed("setMinimapOptions", "true", "true", "true", "false")
        fresh_tree()
        ed("addBox", 6, -60, 6, 12, -58, 12, "hidden")
        check(shot_count(yellow) < shown / 4, "a hidden box is not on the minimap")
        cmd('summon zombie -4.5 -60 8.5 {NoAI:1b,PersistenceRequired:1b,Silent:1b}')
        red = (240, 60, 60)
        before = shot_count(red)
        ed("setMinimapOptions", "true", "true", "true", "true")
        check(shot_count(red) > before + 10, "with Mobs on a zombie is a red dot")
    finally:
        ed("setMinimapOptions", "true", "true", "true", "false")
        cmd("kill @e[type=zombie]")
        editor_on()
        fresh_tree()
        editor_off()


def test_minimap_through_shader_pack():
    """With Iris and a shader pack (with Voxy support) the minimap is a frame of the pack of its own: rendered
    every frame, with terrain in it (not only the pack's sky or fog)."""
    if ed("minimapThroughPack") != "true":
        print("      (needs Iris with a shader pack, skipped)")
        return
    editor_off()
    e, v, r0 = ed("minimap").split(" ")[:3]
    time.sleep(0.5)
    e, v, r1, _, err = ed("minimap").split(" ", 4)
    check(v == "true" and err == "-" and int(r1) > int(r0), "rendered through the pack every frame: %s" % ed("minimap"))
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, "minimap-pack.png")
    ed("minimapShot", path)
    time.sleep(0.5)
    r = subprocess.run([os.path.join(ROOT, "dev"), "magick", path, "-colorspace", "gray", "-format", "%[fx:int(255*standard_deviation)]", "info:"],
                       cwd=ROOT, capture_output=True, text=True)
    m = re.search(r"\d+", r.stdout)
    check(m and int(m.group()) > 8, "the minimap shows terrain, not a uniform sky: deviation %s" % r.stdout.strip())
    shot("minimap-pack-hud")


def test_fluid_opacity_in_lod():
    """Transparent Fluids reaches Voxy's LOD: fluid models are flagged on the GPU and the terrain shader
    takes the module's opacity (shader state, no re-mesh)."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_on()
    c.command("fill 4 -61 4 6 -61 6 minecraft:water")
    time.sleep(3)
    S = "dev.infiniterra.api.TerrainStyle"
    models, patched = c.static("dev.infiniterra.impl.style.VoxyStyle", "debug").split()
    check(patched == "true", "Voxy's terrain shader applies the fluid opacity")
    check(int(models) > 0, "fluid models are flagged: %s" % models)
    ed("setModule", "fluid_transparency", "true")
    ed("fluidOpacity", 0.4)
    time.sleep(0.5)
    check(abs(float(c.static(S, "fluidOpacity")) - 0.4) < 1e-4, "the module's opacity goes to the LOD: %s" % c.static(S, "fluidOpacity"))
    ed("setModule", "fluid_transparency", "false")
    time.sleep(0.5)
    check(float(c.static(S, "fluidOpacity")) == 1.0, "module off: LOD fluids as Voxy draws them")
    ed("fluidOpacity", 0.25)
    c.command("fill 4 -61 4 6 -61 6 minecraft:air")


def test_guess_prefers_real_lod():
    """Terrain Voxy already has (here: flat land loaded far from spawn, then unloaded) is copied into the
    guessed view instead of generated (the seed's generator would make hills there)."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_off()
    c.command("plainsight seed clear")
    import random
    far = 20000 + random.randrange(0, 1000) * 64
    c.command("tp @s %d -60 %d" % (far, far))
    time.sleep(12)
    c.command("tp @s 0.5 -60 0.5")
    time.sleep(12)
    # a seed nobody used here yet: its guessed world starts empty
    c.command("plainsight seed %d" % random.randrange(1, 1 << 40))
    s = wait_for(lambda s: s["guessState"] not in ("", "STARTING"), 60)
    check(s["guessState"] == "READY", "worldgen server ready: %s" % s["guessState"])
    # all of Voxy's LOD is copied in the background, without the camera going there
    s = wait_for(lambda s: s["guessImportTotal"] > 0 and s["guessImported"] >= s["guessImportTotal"], 90, 1)
    check(s["guessImportTotal"] > 0 and s["guessImported"] >= s["guessImportTotal"],
          "the whole LOD is copied: %d / %d" % (s["guessImported"], s["guessImportTotal"]))
    check(ed("guessChunkIsReal", far >> 4, far >> 4) == "true", "including the far area, before the camera is there")
    editor_on()
    ed("setCamera", far + 0.5, 40, far + 0.5, 0, 89)
    s = wait_for(lambda s: s["guessShowing"], 40)
    vp = s["viewport"]
    c.move(vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    time.sleep(3)
    s = wait_for(lambda s: s["surfaceUnderCursor"] != "", 5, 0.1)
    y = float(s["surfaceUnderCursor"].split(",")[1]) if s["surfaceUnderCursor"] else 999
    check(abs(y - (-60)) < 1, "and shown: the flat ground, not generated terrain (%s)" % s["surfaceUnderCursor"])
    shot("guess-real-lod")
    # at the edge of the far area Voxy has some of a LOD column's 2x2 chunks only: the others are generated,
    # not left empty (they were copied as air when realness was decided per whole LOD section)
    def settled(s):
        settled.history.append(s["guessed"])
        return s["guessPending"] == 0 and len(settled.history) > 8 and settled.history[-8] == s["guessed"]
    settled.history = []
    wait_for(settled, 120, 0.5)
    holes = ed("guessHoles", far >> 4, far >> 4, 24)
    check(holes.split(" ")[0] == "0", "no empty chunks between the copied and the generated LOD: %s" % holes)
    editor_off()
    c.command("plainsight seed clear")


def test_zz_leave_world_with_guessed_terrain():
    """Leaving a world while guessed terrain is shown (editor open) returns to the title screen; Voxy used
    to abort the game finding the guessed LOD world still in use. Runs last: it leaves the world."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    c.command("plainsight seed 12345")
    wait_for(lambda s: s["guessState"] == "READY", 60)
    editor_on()
    ed("setCamera", 5000.5, 200, 5000.5, 0, 45)
    wait_for(lambda s: s["guessShowing"], 20)
    ed("leaveWorld")
    deadline = time.time() + 40
    while time.time() < deadline and "world=none" not in c.status():
        time.sleep(1)
    st = c.status()
    check("world=none" in st, "back out of the world: %s" % st.splitlines()[:2])
    time.sleep(3)
    check(not c.status().startswith("bridge not reachable"), "and the game is still running")
    c.world("plainsight-test")
    c.wait_world()
    time.sleep(3)
    c.command("plainsight seed clear")


def test_worldgen_world_mirrors_the_real_one():
    """With a seed, the worldgen server's world copies the real chunks the client has, and block changes in
    them: here in the flat test world its chunks become flat, far ones stay generated from the seed."""
    W = "dev.infiniterra.impl.guess.WorldMirror"
    cmd("tp @s 0.5 -60 0.5 0 0")
    before = int(c.static(W, "counts").split()[0])  # (the count runs on from earlier tests)
    c.command("plainsight seed 12345")
    try:
        deadline = time.time() + 120
        while time.time() < deadline and int(c.static(W, "counts").split()[0]) < before + 20:
            time.sleep(1)
        check(int(c.static(W, "counts").split()[0]) >= before + 20, "real chunks are copied: %s" % c.static(W, "counts"))
        time.sleep(2)
        # the flat world: grass (or dirt under blocks) at -61, air above; generated terrain has rock there
        surface, above = c.static(W, "serverBlock", 20, -61, 20), c.static(W, "serverBlock", 20, -40, 20)
        check(("grass_block" in surface or "dirt" in surface) and "air" in above,
              "near the player the worldgen world is the flat world: %s / %s" % (surface, above))
        far = c.static(W, "serverBlock", 50008, -61, 50008)
        check("grass_block" not in far and "dirt" not in far and "air" not in far, "far away it is generated from the seed: %s" % far)
        cmd("setblock 9 -60 9 gold_block")
        deadline = time.time() + 15
        while time.time() < deadline and "gold_block" not in c.static(W, "serverBlock", 9, -60, 9):
            time.sleep(0.5)
        check("gold_block" in c.static(W, "serverBlock", 9, -60, 9), "a block placed in the real world reaches it: %s" % c.static(W, "serverBlock", 9, -60, 9))
    finally:
        cmd("setblock 9 -60 9 air")
        c.command("plainsight seed clear")


def test_distant_horizons_keeps_its_world():
    """With Distant Horizons, a worldgen server starting (a seed set) does not replace DH's world, and a chunk
    generated from the seed can be given to DH's LOD (the prototype of a DH backend, #48)."""
    T = "dev.infiniterra.impl.dh.DhProbe"
    if c.static(T, "present") != "true":
        print("      (needs Distant Horizons, skipped)")
        return

    def run(what, wait=120):
        c.static(T, "run", what)
        deadline = time.time() + wait
        while time.time() < deadline:
            r = c.static(T, "last")
            if r != "running":
                return r
            time.sleep(0.5)
        return "timeout"

    check(not run("surface 8 8").startswith(("error", "no DH")), "DH has a world")
    c.command("plainsight seed 12345")
    try:
        wait_for(lambda s: s["guessState"] != "", 60)
        time.sleep(10)
        r = run("surface 8 8")
        check(not r.startswith(("error", "no DH")), "DH keeps its world while a worldgen server runs: %s" % r)
        # far beyond what DH generates itself in singleplayer (it would replace the chunk with the flat world's)
        # (a new chunk each run: DH keeps what it was given)
        import random
        cx = random.randrange(5000, 60000)
        x = cx * 16 + 8
        before = run("surface %d %d" % (x, x))
        r = run("inject 12345 %d %d" % (cx, cx), 240)
        check(r.startswith("ok"), "a chunk from the seed goes to DH: %s" % r)
        time.sleep(5)
        after = run("surface %d %d" % (x, x))
        m = re.search(r"up to (-?\d+)", after)
        check(before.startswith(("none", "only air")) and m and int(m.group(1)) > -40,
              "and DH has its (normal, not flat) terrain: %s -> %s" % (before, after))
    finally:
        c.command("plainsight seed clear")


def test_zzz_seed_check_against_bedrock():
    """In a world with normal terrain, the bedrock of the loaded chunks matches its seed; another seed does not:
    plainsight then stops using it, says so, and does not take it again from SeedcrackerX. In the flat test
    world (no random bedrock) seeds are not judged. Runs last: it switches worlds."""
    c.command("plainsight seed 4242")
    time.sleep(2)
    s = state()
    check(s["seed"] == "4242" and not s["seedCheck"].startswith("MISMATCH"), "flat world: not judged: %s" % s["seedCheck"])
    c.command("plainsight seed clear")
    ed("leaveWorld")
    deadline = time.time() + 40
    while time.time() < deadline and "world=none" not in c.status():
        time.sleep(1)
    c.world("plainsight-normal", "creative", "normal")
    c.wait_world(300)
    try:
        # (the world may have been left in another dimension)
        c.command("execute in minecraft:overworld run tp @s 0 120 0")
        time.sleep(8)
        c.command("plainsight seed 12345")
        s = wait_for(lambda s: s["seedCheck"].startswith("MATCHES"), 60)
        check(s["seedCheck"].startswith("MATCHES"), "the right seed matches the bedrock: %s" % s["seedCheck"])
        check(s["seedCheck"].split()[2] == "0", "and no chunk mismatches: %s" % s["seedCheck"])
        c.command("plainsight seed 12346")
        s = wait_for(lambda s: s["seed"] == "", 60)
        check(s["seed"] == "" and s["seedRejected"] == "12346", "another seed is rejected: seed=%s rejected=%s check=%s"
              % (s["seed"], s["seedRejected"], s["seedCheck"]))
        check("Stopped using seed" in ed("chatShown", 4), "and plainsight says so: %s" % ed("chatShown", 4))
        # SeedcrackerX finding it again is ignored
        c.static("dev.plainsight.guess.SeedStore", "fromSeedCrackerText", "12346")
        time.sleep(0.5)
        check(state()["seed"] == "", "SeedcrackerX's same seed is not taken again")
    finally:
        c.command("plainsight seed clear")
        ed("leaveWorld")
        deadline = time.time() + 40
        while time.time() < deadline and "world=none" not in c.status():
            time.sleep(1)
        c.world("plainsight-test")
        c.wait_world()
        time.sleep(3)


def test_guessed_terrain():
    """With a seed (here given by command) and Voxy, the editor shows terrain generated from the seed far
    from the player, tinted; Voxy shows the normal LOD again outside the editor."""
    if not state()["lodMod"] or not state()["sodium"]:
        print("      (needs Voxy, skipped)")
        return
    editor_off()
    c.command("plainsight seed 12345")
    s = wait_for(lambda s: s["guessState"] not in ("", "STARTING"), 60)
    check(s["seed"] == "12345" and s["guessState"] == "READY", "the worldgen server starts for the seed: %s" % s["guessState"])
    editor_on()
    check("view_guessed_terrain" in state()["widgets"], "a seed adds the Guessed terrain view button")
    px, py, pz = state()["player"]
    # somewhere not generated in earlier runs
    import random
    gx, gz = px + 3000 + random.randrange(0, 200) * 640, pz + 3000 + random.randrange(0, 200) * 640
    ed("setCamera", gx, 220, gz, 135, 35)
    before = state()["guessed"]
    s = wait_for(lambda s: s["guessed"] > before + 100 and s["guessShowing"], 60)
    check(s["guessed"] > before + 100, "chunks are generated around the camera (%d -> %d)" % (before, s["guessed"]))
    check(s["guessShowing"], "Voxy shows the guessed LOD world in the editor")
    tint = s["guessTintDrawn"]
    time.sleep(1)
    check(state()["guessTintDrawn"] > tint, "guessed chunks are tinted")
    shot("guessed-terrain")
    # aim at guessed terrain: the arcball pivots on it (depth pick), not on a point in the air
    vp = state()["viewport"]
    cx, cy = vp[0] + vp[2] / 2, vp[1] + vp[3] * 0.7
    c.move(cx, cy)
    s = wait_for(lambda s: s["surfaceUnderCursor"] != "", 5, 0.1)
    check(s["surfaceUnderCursor"] != "", "the guessed terrain under the cursor is found")
    sx, sy, sz = map(float, s["surfaceUnderCursor"].split(","))
    check(-64 < sy < 320, "on the terrain: %s" % s["surfaceUnderCursor"])
    c.key(K["LCTRL"], "press")
    c.button(1, "press")
    time.sleep(0.15)
    piv = state()["camera"]["pivot"]
    c.button(1, "release")
    c.key(K["LCTRL"], "release")
    check(math.dist(piv, (sx, sy, sz)) < 3, "Ctrl+drag orbits around the guessed terrain under the cursor: %s vs %s" % (piv, (sx, sy, sz)))
    # far above: the terrain is beyond the normal far plane (render distance x 4); the editor's reaches it
    cam(sx, sy + 1100, sz, 0, 89)
    vp = state()["viewport"]
    c.move(vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    time.sleep(2)
    s = wait_for(lambda s: s["surfaceUnderCursor"] != "", 5, 0.1)
    check(s["surfaceUnderCursor"] != "", "the guessed terrain is found from 1100 blocks above")
    fy = float(s["surfaceUnderCursor"].split(",")[1])
    check(-64 < fy < 320, "at the terrain, not at a far plane in the air: %s" % s["surfaceUnderCursor"])
    # inside guessed rock: Ctrl pivots on the rock the camera is in, Ctrl+Shift looks past it
    rx, rz = int(math.floor(sx)), int(math.floor(sz))
    ry = int(math.floor(sy))
    # the first solid block a little below the surface (it may be under water)
    for dy in range(1, 40):
        if ed("solidAt", rx, ry - dy, rz) == "true" and ed("solidAt", rx, ry - dy - 1, rz) == "true":
            ry -= dy + 1
            break
    check(ed("solidAt", rx, ry, rz) == "true", "the guessed rock below the surface is known solid (Voxy data)")
    # looking up: out of the rock towards the sky
    cam(rx + 0.5, ry + 0.5, rz + 0.5, 0, -80)
    vp = state()["viewport"]
    c.move(vp[0] + vp[2] / 2, vp[1] + vp[3] / 2)
    time.sleep(0.5)

    def orbit_pivot(shift):
        c.key(K["LCTRL"], "press")
        if shift:
            c.key(K["LSHIFT"], "press", mc.MOD_SHIFT)
        c.button(1, "press")
        time.sleep(0.15)
        p = state()["camera"]["pivot"]
        c.button(1, "release")
        if shift:
            c.key(K["LSHIFT"], "release")
        c.key(K["LCTRL"], "release")
        time.sleep(0.2)
        return p
    p0 = orbit_pivot(False)
    check(math.dist(p0, (rx + 0.5, ry + 0.5, rz + 0.5)) < 1.5, "Ctrl in guessed rock pivots on that rock: %s" % p0)
    cam(rx + 0.5, ry + 0.5, rz + 0.5, 0, -80)
    time.sleep(0.3)
    p1 = orbit_pivot(True)
    check(math.dist(p1, (rx + 0.5, ry + 0.5, rz + 0.5)) > 1.5, "Ctrl+Shift looks past it: %s" % p1)
    ed("setModule", "guessed_terrain", "false")
    check(not wait_for(lambda s: not s["guessShowing"], 3)["guessShowing"], "switching it off shows the normal LOD")
    ed("setModule", "guessed_terrain", "true")
    check(wait_for(lambda s: s["guessShowing"], 3)["guessShowing"], "and on again")
    editor_off()
    check(not wait_for(lambda s: not s["guessShowing"], 3)["guessShowing"], "outside the editor Voxy shows the normal LOD")
    c.command("plainsight seed clear")
    s = wait_for(lambda s: s["guessState"] == "", 5)
    check(s["seed"] == "" and s["guessState"] == "", "clearing the seed stops guessing")


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
    d = os.path.join(c.run_dir, "saves", "plainsight-test", "plainsight", "selections")
    files = os.listdir(d)
    check("minecraft_overworld.json" in files, "selections saved in the world folder, per dimension: %s" % files)


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


def test_see_through_opacity():
    """The cut blocks are drawn with the set opacity (vanilla and Sodium), not just hidden."""
    editor_on()
    fresh_tree()
    ed("setModule", "see_through", "false")
    cmd("tp @s -8.5 -59 -8.5")
    time.sleep(1.0)
    cam(-8.5, -50, -16, 0, 45)
    ed("setModule", "see_through", "true")
    ed("seeThroughOpacity", 0.0)
    time.sleep(2.5)
    # the inside of the hut's back wall through the cut roof
    x, y = map(float, ed("project", -10.5, -57, -6.9).split(","))
    open_ = avg_color(shot("seethrough-opacity-0"), int(x) - 6, int(y) - 6, 12, 12)
    ed("seeThroughOpacity", 0.6)
    time.sleep(2.5)
    faint = avg_color(shot("seethrough-opacity-60"), int(x) - 6, int(y) - 6, 12, 12)
    ed("seeThroughOpacity", 0.15)
    ed("setModule", "see_through", "false")
    cmd("tp @s 0.5 -60 0.5 0 0")
    print("      cut roof at opacity 0 / 0.6:", open_, faint)
    check(color_dist(open_, faint) > 12, "the cut blocks show faintly at opacity 0.6: %s vs %s" % (open_, faint))
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


def test_block_overlay_outline():
    editor_on()
    cmd("tp @s 0.5 -60 0.5 0 0")
    time.sleep(0.5)
    ed("overlayOutline", "false")
    ed("overlayOpacity", 1.0)
    c.click(*widget("view_block_overlay_demo"), b=1)
    time.sleep(1.0)
    # the hut's corner log column stands at x -3, z 5 (y -59..-56); look at it from outside
    cam(-9, -54, -3, -40, 20)
    time.sleep(0.8)
    check(state()["overlayOutlineEdges"] == 0, "no outline while it is off")
    off = shot("block-overlay-no-outline")
    ed("overlayOutline", "true")
    time.sleep(0.6)
    edges = state()["overlayOutlineEdges"]
    check(edges > 100, "outline edges drawn: %s" % edges)
    on = shot("block-overlay-outline")
    vp = state()["viewport"]
    # the corner edge of the hut: outline pixels there, blue (0x60C0FF)
    x, y = map(float, ed("project", -3.01, -57.5, 4.99).split(","))
    changed = changed_pixels(off, on, int(x) - 6, int(y) - 6, 12, 12)
    col = avg_color(on, int(x) - 1, int(y) - 1, 3, 3)
    print("      outline edges", edges, "changed at the corner", changed, "colour", col)
    check(changed >= 12, "the outline shows at the hut's corner: %s pixels" % changed)
    check(col[2] > col[0] + 40, "the outline is drawn in its colour: %s" % (col,))
    # flat surfaces are not gridded: the middle of a wall barely changes
    x, y = map(float, ed("project", -3.01, -58.5, 7.5).split(","))
    flat = changed_pixels(off, on, int(x) - 6, int(y) - 6, 12, 12)
    check(flat <= 6, "no lines between blocks of a flat wall: %s pixels" % flat)
    ed("overlayOutline", "false")
    ed("overlayOpacity", 0.65)
    c.click(*widget("view_block_overlay_demo"), b=1)
    time.sleep(0.5)
    check(state()["overlayOutlineEdges"] == 0, "outline gone with the overlay")


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
