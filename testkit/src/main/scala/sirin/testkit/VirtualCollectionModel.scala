package sirin.testkit

/** Full-state interval oracle; no Sirin or engine dependency. */
object VirtualCollectionModel:
  final case class Row(key: Int, generation: Long, draft: Int)
  final case class State(source: Vector[Int] = Vector.range(0, 30), minimum: Int = 0,
      offset: Int = 0, height: Int = 0, revision: Long = 0, rows: Vector[Row] = Vector.empty, next: Long = 1)
  enum Op:
    case Scroll(offset: Int, height: Int)
    case Filter(minimum: Int)
    case Assign(source: Vector[Int])
    case Draft(key: Int, value: Int)
  def transact(state: State, edits: Vector[Op], refuse: Boolean): State =
    if refuse then state
    else
      var source = state.source; var minimum = state.minimum; var offset = state.offset; var height = state.height
      var drafts = state.rows.map(r => r.key -> r.draft).toMap
      edits.foreach {
        case Op.Scroll(o, h) => offset = o; height = h
        case Op.Filter(m) => minimum = m
        case Op.Assign(s) => source = s
        case Op.Draft(k, v) => drafts = drafts.updated(k, v)
      }
      val projected = source.filter(_ >= minimum)
      // Overscan one row: include every row intersecting the expanded pixel interval.
      val visible = if height == 0 then Vector.empty else projected.zipWithIndex.collect {
        case (key, index) if (index + 1) * 10 > math.max(0, offset - 10) && index * 10 < offset + height + 10 => key
      }
      var next = state.next
      val rows = visible.map { key => state.rows.find(_.key == key) match
        case Some(row) => row.copy(draft = drafts(key))
        case None => val row = Row(key, next, 0); next += 1; row
      }
      state.copy(source = source, minimum = minimum, offset = offset, height = height,
        revision = state.revision + (if projected == state.source.filter(_ >= state.minimum) then 0 else 1), rows = rows, next = next)
