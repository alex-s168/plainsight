# Plainsight

Client-side Fabric mod for Minecraft 26.3. The long-term goal is a "legit" client: automation for
mining, farming and building that looks like a normal player to the server, plus utilities useful on
any SMP, with every automatic-playing feature switchable off. **This first milestone is the editor.**

## Editor

<kbd>Right Shift</kbd> (rebindable: *Controls → Plainsight → Toggle Editor*) opens an Axiom-style
editor in **any game mode**. It is purely client-side: the camera is a detached free camera, nothing
is sent to the server, and the player stands still where they were (and is drawn, third person).
<kbd>M</kbd> (*Open Editor Map*, rebindable) opens it on the map, centred on the player; in the editor
the same key switches between the map and the 3D view. With Axiom installed (same default key), the
*Axiom* button at the top right of the viewport switches to Axiom's editor; Right Shift there closes it.

<kbd>J</kbd> (*Show Editor Overlays In Game*, rebindable) draws the editor's overlays in the normal game
as well: the selection tree's boxes and entity outlines, the light overlay, and enabled block and path
overlays (which otherwise only show in the editor).

Layout: menu bar, tool bar with *Tool Options* on the left, *Selections* tree and *Properties* on the
right, status bar with hints and the camera **Speed** slider at the bottom. The world is rendered with
a projection centred on the viewport between the panels.

### Camera (same bindings as Axiom's defaults)

| Input | Action |
|---|---|
| LMB drag | rotate (look around) |
| Ctrl + LMB drag | arcball around the block or entity under the cursor (marked while orbiting; an entity pivot moves with the entity); the horizon stays level. |
| Ctrl + Shift + LMB drag | arcball as above, but the pivot search first leaves the solid blocks the camera is in (underground: orbit the cave you look at, not the rock around the camera) |
| Ctrl + RMB drag | pan; the grabbed point stays under the cursor |
| Scroll | move towards / away from the point under the cursor (never past it), gliding over a fraction of a second; after an arcball orbit (or while following an entity) towards / away from the pivot, keeping the view centred |
| W A S D, Space, Shift | fly (horizontal like creative flight), Ctrl = 3× faster; scaled by *Speed* |
| MMB | pick block (shown in the status bar) |
| RMB | use the active tool |

Right-click an entity in the tree → *Follow with camera*: the camera keeps its offset to the entity
and the arcball orbits it (the viewport shows a *Following …* chip; click it to stop). Shift while
dragging a gizmo means "free move", not "fly down". Holding Ctrl switches the status bar to the Ctrl
controls; *Help* lists everything.
"Under the cursor" means whatever is drawn there, read from the depth buffer (the editor's far plane
reaches 65536 blocks): Voxy's LOD and guessed
terrain work as arcball pivot, pan and zoom target, for box selection and MMB too, not only loaded blocks.
Chat (<kbd>T</kbd>, <kbd>/</kbd>), F-keys and the pause menu still work; <kbd>Esc</kbd> first cancels
what the tool is doing and closes ImGui popups.

### World view (the labelled buttons at the top of the viewport; right-click a button for settings)

* **Transparent fluids**: water and lava drawn translucent (opacity slider, 0 = hidden).
* **See-through to players** (RTS cutaway): a cone from the camera that widens to a large area around
  each player (radius sliders for both ends) is drawn faintly (opacity slider, 0 = hidden), optionally
  for other players too; the ground the player stands on stays. Blocks around the cut are meshed as if
  the cut blocks were air (no holes, no ambient-occlusion shadows) and the opened space, including the
  players in it, is lit with the sky light around the camera instead of the darkness of a closed room.
* **Guessed terrain** (with Voxy, once the world seed is known): terrain generated from the seed wherever
  no real chunks have been seen, so the camera can go anywhere. Guessed chunks get a faint blue tint
  that fades in and out, and they are shown only in the editor; the normal game keeps the normal LOD. The seed comes from
  SeedcrackerX (as soon as it cracks it or finds it in its database) or `/plainsight seed <seed>` (`/plainsight seed world` in
  singleplayer, `/plainsight seed clear`); a given seed is checked against the server's hashed seed.
  Seeds are hidden on screen by default (for screen sharing): chat lines (SeedcrackerX, `/seed`), titles
  and this mod show them as dots; toggle with the editor's Hide seeds module or with `/plainsight seed hide <true|false>`.
  Settings: tint strength, fade cycle (0.2 to 5 s, default 0.5 s), radius around the camera (default 32 chunks, also `/plainsight guess radius <n>`).

  How: a private, headless vanilla server with that seed generates chunks around the editor camera
  (full worldgen: terrain, caves, features, structures; nothing is saved) and hands them to a second
  Voxy LOD world, which the editor renders instead of the normal one. Every real chunk Voxy ingests is
  copied into that world too and replaces the guess, and before generating a chunk the LOD Voxy already
  has there (from earlier sessions too) is copied over instead, so everywhere you have been shows what
  you saw (useful where the server's terrain differs from today's generator). All of the LOD Voxy has
  gathered in that world is also copied in the background (nearest first, once), so the editor shows
  it everywhere, at any distance. This works per chunk and 16-block section: sections Voxy has real data
  for keep it, the rest of a chunk is filled in from generation (chunks without any block in Voxy's LOD
  were never seen, only their LOD section's neighbours; sections without blocks count as unknown where
  terrain runs into them, since Voxy keeps sections it never got data for as air).
  In the editor Voxy also draws LOD right up to the camera (it normally starts 8 to 16 blocks away and
  drops sections the camera is in; its culling shaders are patched for that), and Ctrl / Ctrl+Shift arcball
  pivots know the solid blocks of LOD and guessed terrain from Voxy's data.
  Guesses are only natural terrain: no builds, and chunks generated by older game versions differ.
  State lives in `plainsight/guess/` in the game directory.

### Tool bar modules (right-click for settings)

* **Light Overlay**: colours the top faces of blocks by the light above them, red where block and sky
  light are both at or below the threshold (mobs can spawn any time), yellow where only block light is
  (at night). Threshold (default 0) and radius in the settings; with <kbd>J</kbd> also in the normal game.
* **Editor Brightness** / **In-game Brightness**: brighter lighting while the editor is open / in the
  normal game, up to fullbright (slider). Purely visual: it raises the lightmap like gamma and night vision.
  The editor starts with its brightness on at 0.2.

### Selections

Selections form a tree. Every node is a selection: a **group** is the union of its children, a
**box** selects the blocks whose centres it contains, an **entity** node selects one entity (stored by
UUID). Labels look like `box 1 (500 blocks)`, `player: Steve`; generated names count per kind and are
never reused. The tree is saved per world/server under `config/plainsight/selections/`.

Tree view: click to select (Ctrl+click adds), double-click or F2 to rename, drag & drop to move (top
quarter of a row: before it, bottom quarter: after it, middle of a group: into it), right-click for
*Rename, New group inside, Group selected (Ctrl+G), Ungroup, Duplicate (Ctrl+D), Hide/Show, Focus
camera, Delete (Del)*. *+ Group*, *Group*, *Delete* buttons above the tree. New selections go into the
selected group (or next to the selected node).

**Box Select** works like Axiom's: RMB-drag from one block to another creates a box covering both; the
box has three handles: the start corner, the end corner and the centre. LMB on a handle makes it active
(the end corner is active after creating). The active handle carries a gizmo: arrows (pointing to the
camera's side) and plane squares move it; on a corner this resizes the box along its own axes while
the opposite corner stays put; on the centre it moves the box, and the centre's **rotation rings**
rotate it, so boxes need not be axis-aligned. Moves snap to whole blocks and rotations to 15° (both
configurable; hold Shift for free movement). Arrow keys / Page Up / Page Down nudge the active handle.
Enter, a click on empty space, the *Done* button in the viewport or clicking the box in the tree again
puts the box down (hides its handles). Tool options show name, centre, size and rotation fields.

**Entity Select**: RMB on an entity (players included, also yourself) adds it to the tree.

### Overlays (APIs for later automation, with demos as viewport buttons)

* `BlockOverlay`: blocks drawn as if placed (paste preview): real block models tesselated with the
  world's sky/block light and ambient occlusion, faces between overlay blocks culled, slightly
  translucent by default (`opacity`, slider in the demo's settings). *Block Overlay
  Demo* shows a small hut in front of the player (darker at night, like real blocks).
* `PathOverlay`: path-finding style plan: the path through its nodes (walker progress), the goal, blocks
  to break (red) and to place (blue), optionally faintly visible through terrain. *Path Overlay Demo*
  plans a walk over the terrain in the look direction, tunnelling through walls and bridging gaps.
* `Overlay`: immediate-mode lines/triangles per frame in three layers (depth-tested, always on top,
  and "behind terrain only" for x-ray hints), drawn after the level.

## Minimap (prototype, with Voxy)

A minimap in the top right of the HUD that is Voxy's LOD world seen from a second camera: above and
behind the player, tilted 60 degrees down (the editor map's tilt: Ctrl + mouse up/down there, from
top-down to 10 degrees) and turned with the player's view, so terrain and builds show in 3D. <kbd>N</kbd> switches it off and on, <kbd>=</kbd> / <kbd>-</kbd> zoom (24 m to 6 km, gliding).
An arrow marks the player, a red N the north. Hidden in the editor and with F1.

How: right after Voxy's own frame it renders once more, through an extra Voxy viewport (as for
Vivecraft's second eye) with the minimap camera, into the minimap's textures, with its own set of
Voxy's offscreen targets swapped in (so the main view's are not reallocated). The LOD is drawn up to the
camera and over the loaded chunks too, Voxy keeps loading around the player, and chunks where blocks
change are given to Voxy again (at most once a second), since Voxy otherwise reads loaded chunks only
when they load and unload. Not with shader packs (Iris pipeline). It costs about one more Voxy frame
at the minimap's size (around 0.5 ms on an integrated GPU in a flat world).

## Development

Requires Nix with flakes. `./dev <cmd>` runs a command in the dev shell (JetBrains Runtime 25 for
enhanced hot-swap, headless sway, Mesa, ImageMagick).

```sh
./dev ./gradlew build              # mod jar (bundles imgui-java + natives) and unit tests
./dev ./gradlew runClient          # visible dev client (run/, bridge :25610) with Voxy, Sodium, SeedcrackerX
tools/build.sh [tasks]             # Gradle build that returns as soon as Gradle is done
tools/mc.py start                  # test client in a hidden headless compositor (run-hidden/, bridge :25612)
tools/mc.py world                  # create/open a flat test world
tools/mc.py key 229                # press Right Shift (SDL scancodes, see tools/mc.py)
tools/mc.py state                  # editor state as JSON (dev.plainsight.editor.EditorDebug)
tools/mc.py full shot.png          # screenshot of the whole window including the ImGui UI
tools/mc.py hotswap                # compile and hot-swap changed classes (not mixins)
tools/ingame_test.py [filter...]   # end-to-end tests in the hidden client (screenshots in build/test-shots)
tools/mc.py --axiom start          # vanilla + Axiom in run-axiom/ (bridge :25611), plainsight disabled
```

The visible dev client gets extra mods in `run/mods` (`installDevMods`, versions in `gradle.properties`):
Voxy 0.2.20-beta with the Sodium 0.9.2 it requires, and SeedcrackerX 2.16.2 with Cloth Config. The hidden
test client stays vanilla so the tests exercise vanilla rendering. Voxy only supports OpenGL; the hidden
client uses Vulkan unless started with `GL=1` (`tools/mc.py start gl`).

The dev bridge (`src/devbridge`, never part of the release jar) offers localhost HTTP control: commands,
chat log, keyboard/mouse input through Minecraft's own handlers, screenshots, static method calls, and
direct ImGui input for the Axiom study client.

`tools/ingame_test.py` drives the real input pipeline and checks behaviour through `EditorDebug` and
screenshots: toggling (player never moves), all camera modes, box create/handles/translate/resize/
rotate/nudge/confirm/cancel, typing in fields, entity selection, building the example tree with the
tree view (rename, drag & drop, multi-select, group, reorder, delete), persistence, fluid and see-through
buttons (pixel checks), lit block overlay (day vs night), path overlay, Escape/chat/pause handling.

### Notes

* Sodium is supported through optional mixins (`plainsight.sodium.mixins.json`, applied only when
  Sodium's classes are present): see-through draws the cut blocks faintly in Sodium's translucent pass
  (their neighbours see them as air, and the opened space is lit like the open sky), transparent fluids work for water (lava stays opaque; at opacity 0 fluids are not
  meshed), cave culling is off while the camera is inside rock or outside the loaded chunks, and
  Sodium's render distance reaches the player's chunks while the camera is away.
* The render-distance fog is pushed out by the camera-to-player distance in the editor, so the loaded
  chunks stay visible from a distant camera. Vanilla draws nothing at all once the camera is about a
  render distance away from the player, hence the "No LOD mod" hint in the editor: Voxy or Distant
  Horizons (detected by class, not mod id) fills in the terrain beyond.
* Voxy draws its LOD only where Minecraft drew nothing, which assumes the camera is inside the loaded
  chunks. With the editor camera outside them (`plainsight.voxy.mixins.json`) Voxy depth-tests its LOD
  against the terrain instead, so the LOD in front of the loaded area no longer leaves a hole there.
  Without an LOD mod the side of the loaded area shows its cut face (cave interiors) from outside.

* MC 26.3 renders through *renderpearl* (OpenGL or Vulkan) and uses SDL for windowing. ImGui is drawn
  through the game's GPU abstraction (`ImGuiRenderer`), so it works on both backends; input comes from
  Minecraft's mouse/keyboard handlers (SDL scancodes mapped to ImGui keys).
* Axiom's behaviour was studied only by using its UI in the study client, not from its code. Axiom
  ships ImGui relocated (`imgui.moulberry92`), so both mods' ImGui copies can coexist; both default
  the editor to Right Shift.
* Fabric's renderer API replaces vanilla block tesselation in the chunk compiler, so the see-through
  effect works on the section buffers (`CutState`, `BufferBuilderMixin`) instead of the quad output.
