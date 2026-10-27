// A-0b program 7. The dataset/selection outlive row realization. Fixed-height
// range + delta access is owned by VirtualRows; no snapshot/full-scan binding.
// Deletion clears selection; replacement and reordering retain it by key.
// Unrealized row scopes dispose; this model is owned by the list/workspace.
package sirin.examples.virtualized

import sirin.*

final case class Record(id: Long, label: String)

final class Records(scope: Scope, val rows: CollectionSource[Long, Record]):
  val selected = scope.cell(Option.empty[Long])
  scope.on(rows.changes) { _ =>
    selected.get.foreach { key =>
      if rows.get(key).isEmpty then selected.set(None)
    }
  }
  val select: Command[Long, Unit] = scope.action("rows.select", "Select row") { key =>
    UiTxn.require(rows.get(key).isDefined, "row was removed")
    selected.set(Some(key))
  }

val RecordList = Component.withDeps[Records, Unit]("RecordList") { (model, _, _) =>
  VirtualRows(model.rows, rowHeight = 28.lp, overscan = 4) { row =>
    Text(row.map(_.label))
      .activate(model.select, row.map(_.id), Role.ListItem)
      .semantics((row, model.selected).mapN((r, selection) =>
        Semantics(Role.ListItem, name = Some(r.label), selected = Some(selection.contains(r.id)))))
  }.semantics(Semantics(Role.List, name = Some("Records")))
}
