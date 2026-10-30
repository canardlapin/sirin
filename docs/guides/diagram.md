# Public diagram client

The client lives in `specimens/workbench/src/main/scala/demo/diagram`. Its model,
Element, handlers and named Layout use public toolkit APIs. The desktop entry adds
normal application startup, a window and an Edit menu.

On macOS with JDK 25 and the pinned native dependencies:

```sh
sbt 'desktopJVM/runMain sirin.desktop.DiagramWorkbench'
sbt 'desktopJVM/runMain sirin.desktop.DiagramWorkbench --nodes 512'
sbt desktopJVM/desktopDiagramPackage
```

The package is `desktop/target/package-diagram/SirinDiagram.app`. Building and
checking its signature do not launch it. Real pointer operation and VoiceOver
remain unqualified; automated semantic snapshots do not establish native transport
for individual custom parts.

Drag a node to move it. Shift toggles selection; dragging an already-selected node
moves the group. Secondary drag, Alt-drag or Pan mode moves the camera. The wheel
zooms around the pointer; toolbar zoom uses the last pointer anchor. Escape or
Cancel abandons an active preview. Focus loss also abandons it. A completed node
drag adds one undo entry, regardless of its number of pointer samples.

Arrow keys move selection by eight logical units; Shift-arrow uses one. Primary-Z
undoes, Primary-Shift-Z redoes, and Delete/Backspace deletes selection. The floating
selection tools delete or connect two nodes. Connections run from the smaller ID
to the larger ID; duplicate connections are disabled. Add creates a node at the
last anchor. Reset restores the camera. Pan and zoom do not enter graph history.

The immutable model accepts at most 512 nodes, 2,048 distinct directed edges and
64 undo/redo entries per stack. Node IDs remain unique through deletion and undo.
Labels accept up to 80 UTF-16 code units of valid Unicode. Labels wrap into a
108-unit region and are clipped to 28 units high; full names remain in the
semantic description. Coordinates are finite and bounded to ±1,000,000 logical
units. Camera scale is 0.125–4. Offscreen nodes and connections are culled against
the viewport. These are application bounds, not a promise that every graph within
them fits every renderer work budget.

The dense automated fixture keeps all 512 nodes, 511 connections and 512 labels
visible. GPU tiles begin at 128 pixels and subdivide when one tile would exceed
an existing resource inventory. Tile count, frame pixels, geometry and native
resource limits remain bounded. Indivisible excessive content still refuses.
No latency or cross-platform desktop claim follows from this fixture.
