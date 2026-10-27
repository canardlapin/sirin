// A-0 authoring corpus, program 2: an editable keyed list.
//
// Rows keep their drafts across reordering and across value replacement under
// the same key (VISION §4 law; C1.5b). Exercises KeyedCell, keyed ForEach,
// Component[P] with Inputs[P], Switch inside a row, and per-row commands.
package sirin.examples.list

import sirin.*

final case class TodoId(value: Long)
final case class Todo(id: TodoId, title: String, done: Boolean)

/** The list model. Its scope is the owner of every todo and command. */
final class TodoModel(scope: Scope):
  val todos: KeyedCell[TodoId, Todo] = scope.keyed(
    Seq(
      Todo(TodoId(1), "Draft the abstract", done = false),
      Todo(TodoId(2), "Rerun the pipeline", done = false),
      Todo(TodoId(3), "Email the co-authors", done = true)
    )
  )(_.id)

  private val nextId = scope.cell(4L)

  val add: Command[String, Unit] = scope.action("todo.add", "Add task") { title =>
    UiTxn.require(title.trim.nonEmpty, "empty title")
    val id = nextId.get
    todos.append(Todo(TodoId(id), title.trim, done = false))
    nextId.set(id + 1)
  }

  val rename: Command[(TodoId, String), Unit] = scope.action("todo.rename", "Rename task") { (id, title) =>
    todos.update(id)(_.copy(title = title))
  }

  val toggle: Command[TodoId, Unit] = scope.action("todo.toggle", "Toggle done") { id =>
    todos.update(id)(t => t.copy(done = !t.done))
  }

  val remove: Command[TodoId, Unit] = scope.action("todo.remove", "Delete task")(id => todos.remove(id))

  // AWKWARD (REVIEW #6): enablement is one Signal[Boolean] per command, so
  // "Move up" cannot be disabled for the first row only.
  val moveUp: Command[TodoId, Unit] = scope.action("todo.moveUp", "Move up") { id =>
    todos.indexOf(id).filter(_ > 0).foreach(i => todos.move(id, i - 1))
  }

  val moveDown: Command[TodoId, Unit] = scope.action("todo.moveDown", "Move down") { id =>
    todos.indexOf(id).filter(_ < todos.size - 1).foreach(i => todos.move(id, i + 1))
  }

  /** Simulates a server sync: every value is replaced by a new instance under
    * the same key, and the order is reversed. Open row drafts must survive.
    */
  val sync: Command[Unit, Unit] = scope.action("todo.sync", "Sync") { _ =>
    todos.setAll(todos.snapshot.get.reverse.map(t => t.copy(title = t.title.capitalize)))
  }

/** The row component. AWKWARD (REVIEW #2): the model is a constructor
  * argument rather than a prop, because the row needs it while building its
  * view (outside any transaction). The factory must therefore be called once
  * per list mount; calling it inside the ForEach row function would create a
  * new component type per value change and remount every row.
  */
def todoRow(model: TodoModel): Component[Todo] = Component("TodoRow") { (scope, todo) =>
  val id = todo.map(_.id)

  // Local state, preserved across reorder and same-key replacement.
  val editing = scope.cell(false)
  val draft   = scope.cell("")

  /** The title the draft started from, to detect a concurrent replacement. */
  val base = scope.cell("")

  val changedUnderneath: Signal[Boolean] =
    (editing, base, todo).mapN((e, b, t) => e && b != t.title)

  val field = scope.ref()

  val beginEdit: Action =
    val current = todo.get.title
    draft.set(current)
    base.set(current)
    editing.set(true)
    // OPEN QUESTION (REVIEW Q3): the field is attached by the Switch below in
    // a later pass; is a focus request on a not-yet-attached ref honored?
    field.focus()

  val commit: Action =
    model.rename.dispatch((todo.get.id, draft.get.trim))
    editing.set(false)

  val cancel: Action = editing.set(false)

  Row(
    Checkbox(todo.map(_.done), todo.map(_.title))(_ => model.toggle.dispatch(todo.get.id)),
    Switch(editing) {
      case true =>
        Column(
          TextField.bind(draft).label("Title").onCommit(commit).ref(field).onKey(Chord(Key.Escape))(cancel),
          Switch(changedUnderneath) {
            case true  => Text("This task was changed elsewhere; saving keeps your text.").style(TextStyle.Caption)
            case false => View.empty
          }
        ).grow
      case false =>
        Text(todo.map(_.title)).grow
    },
    Button("Edit").onPress(beginEdit),
    Button("Move up").command(model.moveUp, id),
    Button("Move down").command(model.moveDown, id),
    Button("Delete").command(model.remove, id)
  ).spacing(Spacing.small)
    .align(Alignment.Center)
    .semantics(todo.map(t => Semantics(Role.ListItem, name = Some(t.title))))
}

def todoList(model: TodoModel): Component[Unit] = Component("TodoList") { (scope, _) =>
  val row      = todoRow(model) // once per mount: stable component identity
  val newTitle = scope.cell("")

  val addCurrent: Action =
    model.add.dispatch(newTitle.get)
    newTitle.set("")

  Column(
    Row(
      TextField.bind(newTitle).placeholder("New task").onCommit(addCurrent).grow,
      Button("Add").onPress(addCurrent),
      Button(model.sync)
    ).spacing(Spacing.small),
    Scroll(Column(ForEach(model.todos)(row(_))).spacing(Spacing.xsmall))
      .semantics(Semantics(Role.List, name = Some("Tasks")))
  ).spacing(Spacing.medium).padding(Spacing.large)
}

@main def keyedList(): Unit =
  Application.run { app =>
    val model = TodoModel(app.child("todos"))
    Seq(Window("Tasks", MenuBar.empty)(todoList(model)(())))
  }
