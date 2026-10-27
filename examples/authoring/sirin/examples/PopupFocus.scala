// A-0 authoring corpus, program 4: a popup with focus restoration.
//
// Exercises: a popup owned by its originator but placed in the overlay layer,
// initial focus, focus restoration to whatever had focus when the popup
// opened (the button when clicked, the notes field when opened by shortcut),
// dismissal through the shared machinery, and an effect that closes the
// popup when the layer becomes locked underneath it.
package sirin.examples.popup

import sirin.*

final case class Layer(name: String, locked: Boolean)

/** AWKWARD (REVIEW #3): a component with no props is a Component[Unit] and is
  * placed as `layerHeader(layer)(())`.
  */
def layerHeader(layer: Cell[Layer]): Component[Unit] = Component("LayerHeader") { (scope, _) =>
  val open         = scope.cell(false)
  val draft        = scope.cell("")
  val renameButton = scope.ref()
  val nameField    = scope.ref()

  val beginRename: Command[Unit, Unit] =
    scope.action("layer.rename", "Rename Layer…", enabled = layer.map(l => !l.locked)) { _ =>
      draft.set(layer.get.name)
      open.set(true)
    }

  val commit: Action =
    val name = draft.get.trim
    UiTxn.require(name.nonEmpty, "a layer needs a name")
    layer.update(_.copy(name = name))
    open.set(false) // closing restores focus (FocusRestore.Previous)

  // If a collaborator locks the layer while the popup is open, close it.
  // OPEN QUESTION (REVIEW Q4): focus was to return to the rename button, which
  // the lock has just disabled. Where does focus go?
  scope.effect(layer.map(_.locked)) { locked =>
    if locked then open.set(false)
  }

  Row(
    Text(layer.map(_.name)).style(TextStyle.Heading).grow,
    Checkbox(layer.map(_.locked), Signal.const("Locked"))(v => layer.update(_.copy(locked = v))),
    Button(beginRename).ref(renameButton),
    Popup(
      open,
      anchor = renameButton,
      placement = Placement.Below,
      initialFocus = Some(nameField),
      restoreFocus = FocusRestore.Previous
    )(
      Column(
        TextField.bind(draft).label("Layer name").onCommit(commit).ref(nameField),
        Row(
          Spacer().grow,
          Button("Cancel").onPress(open.set(false)),
          Button("Rename").primary.onPress(commit)
        ).spacing(Spacing.small)
      ).spacing(Spacing.small)
        .padding(Spacing.medium)
        .semantics(Semantics(Role.Dialog, name = Some("Rename layer")))
    )
  ).spacing(Spacing.small)
    .align(Alignment.Center)
    // Opened from anywhere inside the header, including the notes field below.
    .shortcut(Chord(Key.Character('r'), Mods.Primary | Mods.Shift), beginRename)
}

def layerPanel(layer: Cell[Layer]): Component[Unit] = Component("LayerPanel") { (scope, _) =>
  val header = layerHeader(layer)
  val notes  = scope.cell("")
  Column(
    header(()),
    TextField.bind(notes).label("Notes").multiline
  ).spacing(Spacing.medium).padding(Spacing.large)
}

@main def popupFocus(): Unit =
  Application.run { app =>
    val layer = app.cell(Layer("Background", locked = false))
    Seq(Window("Layers", MenuBar.empty)(layerPanel(layer)(())))
  }
