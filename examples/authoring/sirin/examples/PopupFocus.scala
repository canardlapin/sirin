// A-0 authoring corpus, program 4: a popup with focus restoration.
//
// Exercises: a popup owned by its originator but placed in the overlay layer,
// initial focus, focus restoration to whatever had focus when the popup
// opened (the button when clicked, a focused node inside the header by shortcut),
// dismissal through the shared machinery, and an effect that closes the
// popup when the layer becomes locked underneath it.
package sirin.examples.popup

import sirin.*

final case class Layer(name: String, locked: Boolean)

val LayerHeader = Component.withDeps[Cell[Layer], Unit]("LayerHeader") { (layer, scope, _) =>
  val open         = scope.cell(false)
  val draft        = scope.cell("")
  val error        = scope.cell(Option.empty[String])
  val renameButton = scope.ref()
  val nameField    = scope.ref()

  val beginRename: Command[Unit, Unit] =
    scope.action("layer.rename", "Rename Layer…", enabled = layer.map(l => !l.locked)) { _ =>
      draft.set(layer.get.name)
      open.set(true)
    }

  val commit: Action =
    UiTxn.attempt {
      val name = draft.get.trim
      UiTxn.require(name.nonEmpty, "a layer needs a name")
      UiTxn.require(!layer.get.locked, "layer is locked")
      layer.update(_.copy(name = name))
      open.set(false) // successful closing restores focus
    } match
      case Attempt.Accepted(_) => error.set(None)
      case Attempt.Refused(reason) => error.set(Some(reason))

  // If a collaborator locks the layer while the popup is open, close it.
  // Disabled-but-focusable originators remain eligible, otherwise ancestor/default.
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
      restoreFocus = FocusRestore.Previous,
      onDismiss = reason =>
        // Outside clicks cannot discard an edited draft; Escape is explicit cancellation.
        if reason == DismissReason.OutsidePress && draft.get != layer.get.name then DismissDecision.Veto
        else DismissDecision.Allow
    )(
      Column(
        TextField.bind(draft).label("Layer name").error(error).onCommit(commit).ref(nameField),
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
    // Active inside the header; the sibling notes field is outside this shortcut scope.
    .shortcut(Chord(Key.Character('r'), Mods.Primary | Mods.Shift), beginRename)
}

val LayerPanel = Component.withDeps[Cell[Layer], Unit]("LayerPanel") { (layer, scope, _) =>
  val header = LayerHeader.bind(layer)
  val notes  = scope.cell("")
  Column(
    header.view,
    TextField.bind(notes).label("Notes").multiline
  ).spacing(Spacing.medium).padding(Spacing.large)
}

@main def popupFocus(): Unit =
  Application.run { app =>
    val layer = app.cell(Layer("Background", locked = false))
    Windows.single("layers")(_ => Window("Layers", MenuBar.empty)(LayerPanel.bind(layer).view))
  }
