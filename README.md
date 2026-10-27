# Plainsight

Client-side Fabric mod for Minecraft 26.3. The long-term goal is a "legit" client: automation for
mining, farming and building that looks like a normal player to the server, plus utilities useful on
any SMP, with every automatic-playing feature switchable off. **This first milestone is the editor.**

## Editor

<kbd>Right Shift</kbd> (rebindable: *Controls → Plainsight → Toggle Editor*) opens an Axiom-style
editor in **any game mode**. It is purely client-side: the camera is a detached free camera, nothing
is sent to the server, and the player stands still where they were (and is drawn, third person).

<kbd>J</kbd> (*Show Editor Overlays In Game*, rebindable) draws the selection tree's boxes and entity
outlines in the normal game as well; block and path overlays are shown whenever they are enabled.

Layout: menu bar, tool bar with *Tool Options* on the left, *Selections* tree and *Properties* on the
right, status bar with hints and the camera **Speed** slider at the bottom. The world is rendered with
a projection centred on the viewport between the panels.

### Camera (same bindings as Axiom's defaults)

| Input | Action |
|---|---|
| LMB drag | rotate (look around) |
| Ctrl + LMB drag | arcball around the block or entity under the cursor (marked while orbiting; an entity pivot moves with the entity); the horizon stays level. |
| Ctrl + RMB drag | pan; the grabbed point stays under the cursor |
| Scroll | move towards / away from the point under the cursor (never past it) |
| W A S D, Space, Shift | fly (horizontal like creative flight), Ctrl = 3× faster; scaled by *Speed* |
| MMB | pick block (shown in the status bar) |
| RMB | use the active tool |

Right-click an entity in the tree → *Follow with camera*: the camera keeps its offset to the entity
and the arcball orbits it (the viewport shows a *Following …* chip; click it to stop). Shift while
dragging a gizmo means "free move", not "fly down". Holding Ctrl switches the status bar to the Ctrl
controls; *Help* lists everything.
Chat (<kbd>T</kbd>, <kbd>/</kbd>), F-keys and the pause menu still work; <kbd>Esc</kbd> first cancels
what the tool is doing and closes ImGui popups.

### World view (the labelled buttons at the top of the viewport; right-click a button for settings)

* **Transparent fluids**: water and lava drawn translucent (opacity slider, 0 = hidden).
* **See-through to players** (RTS cutaway): a cone from the camera that widens to a large area around
  each player (radius sliders for both ends) is drawn faintly (opacity slider, 0 = hidden), optionally
  for other players too; the ground the player stands on stays. Blocks around the cut are meshed as if
  the cut blocks were air (no holes, no ambient-occlusion shadows) and the opened space, including the
  players in it, is lit with the sky light around the camera instead of the darkness of a closed room.

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

### Overlays (APIs for later automation, with demos in the tool bar)

* `BlockOverlay`: blocks drawn as if placed (paste preview): real block models tesselated with the
  world's sky/block light and ambient occlusion, faces between overlay blocks culled, slightly
  translucent by default (`opacity`, slider in the demo's settings). *Block Overlay
  Demo* shows a small hut in front of the player (darker at night, like real blocks).
* `PathOverlay`: path-finding style plan: the path through its nodes (walker progress), the goal, blocks
  to break (red) and to place (blue), optionally faintly visible through terrain. *Path Overlay Demo*
  plans a walk over the terrain in the look direction, tunnelling through walls and bridging gaps.
* `Overlay`: immediate-mode lines/triangles per frame in three layers (depth-tested, always on top,
  and "behind terrain only" for x-ray hints), drawn after the level.

## Development

Requires Nix with flakes. `./dev <cmd>` runs a command in the dev shell (JetBrains Runtime 25 for
enhanced hot-swap, headless sway, Mesa, ImageMagick).

```sh
./dev ./gradlew build              # mod jar (bundles imgui-java + natives) and unit tests
./dev ./gradlew runClient          # visible dev client (run/, bridge :25610)
tools/mc.py start                  # test client in a hidden headless compositor (run-hidden/, bridge :25612)
tools/mc.py world                  # create/open a flat test world
tools/mc.py key 229                # press Right Shift (SDL scancodes, see tools/mc.py)
tools/mc.py state                  # editor state as JSON (dev.plainsight.editor.EditorDebug)
tools/mc.py full shot.png          # screenshot of the whole window including the ImGui UI
tools/mc.py hotswap                # compile and hot-swap changed classes (not mixins)
tools/ingame_test.py [filter...]   # end-to-end tests in the hidden client (screenshots in build/test-shots)
tools/mc.py --axiom start          # vanilla + Axiom in run-axiom/ (bridge :25611), plainsight disabled
```

The dev bridge (`src/devbridge`, never part of the release jar) offers localhost HTTP control: commands,
chat log, keyboard/mouse input through Minecraft's own handlers, screenshots, static method calls, and
direct ImGui input for the Axiom study client.

`tools/ingame_test.py` drives the real input pipeline and checks behaviour through `EditorDebug` and
screenshots: toggling (player never moves), all camera modes, box create/handles/translate/resize/
rotate/nudge/confirm/cancel, typing in fields, entity selection, building the example tree with the
tree view (rename, drag & drop, multi-select, group, reorder, delete), persistence, fluid and see-through
buttons (pixel checks), lit block overlay (day vs night), path overlay, Escape/chat/pause handling.

### Notes

* MC 26.3 renders through *renderpearl* (OpenGL or Vulkan) and uses SDL for windowing. ImGui is drawn
  through the game's GPU abstraction (`ImGuiRenderer`), so it works on both backends; input comes from
  Minecraft's mouse/keyboard handlers (SDL scancodes mapped to ImGui keys).
* Axiom's behaviour was studied only by using its UI in the study client, not from its code. Axiom
  ships ImGui relocated (`imgui.moulberry92`), so both mods' ImGui copies can coexist; both default
  the editor to Right Shift.
* Fabric's renderer API replaces vanilla block tesselation in the chunk compiler, so the see-through
  effect works on the section buffers (`CutState`, `BufferBuilderMixin`) instead of the quad output.
