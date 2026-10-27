// A-0b program 11. Keyed windows have independent scopes; a single document
// and bounded serial save command belong to the workspace. Closing a window
// removes its key and observers but cannot cancel the workspace save. Closing
// the workspace retires it. Save refusal/errors use the shared command path.
package sirin.examples.multiwindow

import sirin.*
import sirin.examples.document.{Document, SlowDisk, editor, menu}

final case class EditorWindow(id: Long, label: String)

@main def multiWindowDocument(): Unit =
  Application.run { app =>
    val workspace = app.child("workspace")
    val doc = Document(workspace.child("document"), SlowDisk, "Shared text")
    val windows = workspace.keyed(Seq(EditorWindow(1, "Editor"), EditorWindow(2, "Second view")))(_.id)
    Windows(windows) { (_, window) =>
      Window(window.map(_.label), menu(doc))(editor(doc))
        .onClose(windows.remove(window.get.id))
    }
  }
