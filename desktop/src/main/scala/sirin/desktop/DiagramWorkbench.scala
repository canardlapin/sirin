package sirin.desktop

import demo.diagram.*
import sirin.*

/** Public-API diagram entry; startup and native services use the normal application provider. */
object DiagramWorkbench:
  def main(args: Array[String]): Unit =
    val initial = args.toVector match
      case Vector() | Vector("interactive") => DiagramGraph.sample
      case Vector("--nodes", count)         => DiagramGraph.grid(count.toInt)
      case _ => throw new IllegalArgumentException("Usage: DiagramWorkbench [--nodes 0..512]")
    Application.run { scope =>
      val editor = new DiagramEditor(scope.child("diagram"), initial)
      val menu = MenuBar(Menu(
        "Edit",
        MenuItem(editor.undo, Chord.primary('z')),
        MenuItem(editor.redo, Chord.primaryShift('z')),
        MenuItem(editor.delete),
        MenuItem(editor.connect)
      ))
      Windows.single("diagram")(_ => Window("Sirin Diagram", menu)(editor.view))
    }
