package sirin.desktop

import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.{WordKind,UnicodeAnalysis}

/** Owner-confined event policy. Pure editor states publish only after UiTxn. */
final class EditorController(runtime: HeadlessRuntime,analyze: String => UnicodeAnalysis,
    readClipboard: () => String,writeClipboard: String => Unit,clearComposition: () => Unit):
  val interaction=new Interaction
  private var states=Map.empty[Long,TextEditing]
  private var captured=CapturedTree(Node(NodeId(Vector("empty")),Content.Empty),Map.empty)
  private var shown=Option.empty[(Long,LayoutResult)]
  private var drag=Option.empty[(Long,TextPosition)]
  private var refusals=0
  def rejected: Int=refusals
  def snapshots: Map[Long,EditSnapshot]=states.view.mapValues(_.snapshot).toMap
  def state(id: Long): TextEditing=states(id)
  def focusedId: Option[Long]=interaction.focused.flatMap(_.kind match
    case TargetKind.Editor(id) => Some(id)
    case _ => None)
  private def boundaries(s: String): Vector[Int] =
    val a=analyze(s);a.graphemeBoundaries.map(o => a.source.utf16Offset(o).get.value)
  private def words(s: String,backward: Boolean): Vector[Int] =
    val a=analyze(s)
    (Vector(0,s.length)++a.words.filter(_.kind!=WordKind.NonWord).map(w => a.source.utf16Offset(if backward then w.range.start else w.range.end).get.value)).distinct.sorted.filter(boundaries(s).contains)
  def update(value: CapturedTree): Unit =
    captured=value
    if states.exists((id,state) => !value.editors.contains(id) && state.snapshot.preedit.nonEmpty) then clearComposition()
    states=states.filter((id,_) => value.editors.contains(id))
    def values(node: Node): Vector[(Long,String)] = node.content match
      case Content.Editable(id,text,_,_,_,_) => Vector(id->text)
      case Content.Reference(_,child) => values(child)
      case Content.Styled(_,child) => values(child)
      case Content.Stack(_,_,_,children) => children.flatMap(values)
      case Content.Scroll(_,child) => values(child)
      case Content.Virtual(_,_,_,_,children) => children.flatMap(values)
      case _ => Vector.empty
    values(value.tree).foreach { (id,text) =>
      val old=states.getOrElse(id,TextEditing(text));val next=old.sync(text,boundaries)
      if old.snapshot.preedit.nonEmpty && next.snapshot.preedit.isEmpty then clearComposition()
      states=states.updated(id,next)
    }
    drag=drag.filter((id,_) => states.contains(id))
  def install(revision: Long,layout: LayoutResult): Unit =
    shown=Some((revision,layout));interaction.install(InteractionSnapshot(revision,layout),layout.scrollOffsets)
  def presentedRevision: Long=shown.fold(0L)(_._1)
  def layout: Option[LayoutResult]=shown.map(_._2)
  private def placed(id: Long): Option[Placed]=shown.flatMap(_._2.fields.find(_.node.id==NodeId(Vector("editor",id))))
  def invalidatePresentation(): Unit={shown=None;drag=None}
  def ready: Boolean=shown.exists { (_,layout) => layout.editors==snapshots && layout.root.node==captured.tree }
  def needsGeometry(input: HostInput): Boolean=if shown.isEmpty then true else input match
    case _: HostInput.Down | _: HostInput.Up | _: HostInput.Move => true
    case HostInput.KeyDown(key,_,_) => Set(Key.ArrowLeft,Key.ArrowRight,Key.ArrowUp,Key.ArrowDown,Key.Home,Key.End,Key.Tab).contains(key)
    case _ => false
  private def publishFocus()(using UiTxn): Unit =
    val (direct,ancestors,keyboard)=interaction.focusReferences;runtime.publishFocus(direct,ancestors,keyboard)
  private def applyState(id: Long,next: TextEditing,commit: Boolean=false): Boolean =
    captured.editors.get(id).exists { binding =>
      val old=states(id)
      val result=runtime.transact {
        val field=binding.description
        UiTxn.require(field.value.get==old.snapshot.text,"text changed before edit publication")
        if next.snapshot.text!=old.snapshot.text then field.value.set(next.snapshot.text)
        if commit then field.commit(summon[UiTxn])
        publishFocus()
      }
      result match
        case CommitResult.Committed(_) => states=states.updated(id,next);true
        case _ => refusals+=1;false
    }
  def invoke(id: Long): Unit = captured.bindings.get(id).filter(_.enabled).foreach { action =>
    runtime.transact {action.run(summon[UiTxn]);publishFocus()};()
  }
  private def blur(old: Option[Long]): Boolean=old.forall { id =>
    captured.editors.get(id).forall { binding =>
      runtime.transact {binding.description.blur(summon[UiTxn])} match
        case CommitResult.Committed(_) =>
          if states(id).snapshot.preedit.nonEmpty then clearComposition()
          states=states.updated(id,states(id).cancel);true
        case _ => refusals+=1;false
    }
  }
  private def point(id: Long,x: Double,y: Double): Option[TextPosition]=placed(id).map { p =>
    val origin=shown.get._2.textOrigin(p)
    p.text.get.hit(Point((x-origin.x.toDouble).lp,(y-origin.y.toDouble).lp)).primary.position
  }
  private def clean(id: Long,value: String): String =
    val normal=TextEditing.normalize(value)
    if captured.editors(id).description.multiple then normal.replace("\t","    ") else normal.replace('\n',' ').replace('\t',' ')
  var notice=Option.empty[String]
  def handle(input: HostInput,mods: Mods=Mods.none,clicks: Int=1): Unit =
    try dispatch(input,mods,clicks)
    catch
      case _: IllegalArgumentException =>
        refusals+=1;notice=Some("This edit exceeds the field limit or has an invalid text range.")
      case _: NativeWindow.ClipboardUnavailable =>
        refusals+=1;notice=Some("Clipboard is unavailable.")
  def focusReference(reference: Long): Boolean =
    val before=interaction.state;val previous=focusedId
    if !interaction.focusRef(reference) then false
    else if previous!=focusedId && !blur(previous) then {interaction.rollbackPublication(before);false}
    else {runtime.transact {publishFocus()};true}
  def focusEditor(id: Long): Unit = if states.contains(id) then
    val old=focusedId
    if old.contains(id) || blur(old) then
      interaction.state=interaction.state.copy(focused=Some(NodeId(Vector("editor",id))),keyboard=true,pressed=None)
      runtime.transact {publishFocus()};()
  private def dispatch(input: HostInput,mods: Mods,clicks: Int): Unit =
    val oldFocus=focusedId
    input match
      case HostInput.TextInput(value) => focusedId.foreach { id =>
        applyState(id,if states(id).snapshot.preedit.nonEmpty then states(id).commit(clean(id,value),boundaries) else states(id).replace(clean(id,value),boundaries,coalesce=true))
      }
      case HostInput.Composition(value,start,length) => focusedId.foreach { id =>
        // SDL Cocoa reports codepoint indices; public positions are UTF-16.
        val count=value.codePointCount(0,value.length)
        val a=math.max(0,math.min(count,start));val b=math.max(a,math.min(count,if length<0 then a else a+length))
        applyState(id,states(id).mark(clean(id,value),clean(id,value.take(value.offsetByCodePoints(0,a))).length,clean(id,value.take(value.offsetByCodePoints(0,b))).length))
      }
      case HostInput.Deactivate =>
        blur(oldFocus);drag=None;interaction.handle(input);()
      case HostInput.Down(x,y,true) =>
        val before=interaction.state;interaction.handle(input)
        if oldFocus!=focusedId && !blur(oldFocus) then interaction.rollbackPublication(before)
        focusedId.foreach { id =>
          if states(id).snapshot.preedit.nonEmpty then clearComposition()
          point(id,x,y).foreach { p =>
            val old=states(id).cancel
            val selection=if clicks>=3 then EditSelection(TextPosition(0,TextAffinity.Downstream),TextPosition(old.snapshot.text.length,TextAffinity.Upstream))
            else if clicks==2 then
              val a=analyze(old.snapshot.text);val offset=p.utf16Offset
              val word=a.words.find { w =>
                val from=a.source.utf16Offset(w.range.start).get.value;val to=a.source.utf16Offset(w.range.end).get.value
                offset>=from && offset<to
              }
              word.fold(EditSelection.at(offset))(w => EditSelection(TextPosition(a.source.utf16Offset(w.range.start).get.value,TextAffinity.Downstream),TextPosition(a.source.utf16Offset(w.range.end).get.value,TextAffinity.Upstream)))
            else EditSelection(if mods.has(Mods.Shift) then old.snapshot.selection.anchor else p,p)
            if applyState(id,old.select(selection,boundaries)) then drag=Some(id->selection.anchor)
          }
        }
        runtime.transact {publishFocus()};()
      case HostInput.Move(x,y,true) => drag.foreach { (id,anchor) => point(id,x,y).foreach { p => applyState(id,states(id).select(EditSelection(anchor,p),boundaries)) }}
      case _: HostInput.Up =>
        val decision=interaction.handle(input);drag=None;decision.activate.foreach(invoke)
      case HostInput.KeyDown(Key.Tab,_,_) =>
        val before=interaction.state;interaction.handle(input)
        if oldFocus!=focusedId && !blur(oldFocus) then interaction.rollbackPublication(before)
        runtime.transact {publishFocus()};()
      case HostInput.KeyDown(key,keys,_) if focusedId.nonEmpty =>
        val id=focusedId.get;val current=states(id);val primary=keys.has(Mods.Primary);val shift=keys.has(Mods.Shift);val word=keys.has(Mods.Alt)
        def apply(next: TextEditing): Unit={applyState(id,next);()}
        if current.snapshot.preedit.nonEmpty then
          if key==Key.Escape then {clearComposition();apply(current.cancel)}
          // Platform IME consumes editing keys; never run host shortcuts beneath preedit.
        else key match
          case Key.Character(c) if primary => c.toLower match
            case 'a' => apply(current.selectAll)
            case 'c' =>
              if !current.snapshot.selection.empty then writeClipboard(current.selected)
              apply(current.cancel)
            case 'x' => if !current.snapshot.selection.empty then {writeClipboard(current.selected);apply(current.replace("",boundaries))}
            case 'v' => apply(current.replace(clean(id,readClipboard()),boundaries))
            case 'z' => apply(if shift then current.redo else current.undo)
            case _ => ()
          case Key.Backspace | Key.Delete => apply(current.delete(key==Key.Backspace,word,boundaries,words(current.snapshot.text,key==Key.Backspace)))
          case Key.ArrowLeft | Key.ArrowRight | Key.ArrowUp | Key.ArrowDown | Key.Home | Key.End =>
            placed(id).foreach { p =>
              val actual=if primary && key==Key.ArrowLeft then Key.Home else if primary && key==Key.ArrowRight then Key.End else key
              apply(current.navigate(p.text.get,actual,shift,word,primary && (key==Key.Home || key==Key.End),boundaries,words(current.snapshot.text,key==Key.ArrowLeft)))
            }
          case Key.Enter =>
            if !captured.editors(id).description.multiple || primary then {applyState(id,current.cancel,commit=true);()}
            else apply(current.replace("\n",boundaries))
          case Key.Escape => ()
          case _ => () // Characters arrive only through committed text events.
      case _ =>
        val decision=interaction.handle(input);decision.activate.foreach(invoke)
        runtime.transact {publishFocus()};()
  def accessibility(action: NativeWindow.AccessibilityAction): Boolean =
    if !ready || action.revision()!=presentedRevision then false
    else
      val id=action.target()
      if action.kind()==4 && captured.bindings.get(id).exists(_.enabled) then {invoke(id);true}
      else if !states.contains(id) then false
      else action.kind() match
        case 1 =>
          val before=interaction.state
          interaction.state=interaction.state.copy(focused=Some(NodeId(Vector("editor",id))),keyboard=true,pressed=None)
          if before.focused!=interaction.state.focused && !blur(before.focused.flatMap(n => interaction.presented.target(n).flatMap(_.kind match
            case TargetKind.Editor(i) => Some(i)
            case _ => None))) then {interaction.rollbackPublication(before);false}
          else {runtime.transact {publishFocus()};true}
        case 2 =>
          val start=action.start();val end=start.toLong+action.length()
          val stops=boundaries(states(id).snapshot.text)
          if start<0 || end>states(id).snapshot.text.length || !stops.contains(start) || !stops.contains(end.toInt) then false
          else applyState(id,states(id).select(EditSelection(TextPosition(start,TextAffinity.Downstream),TextPosition(end.toInt,TextAffinity.Upstream)),boundaries))
        case 3 => applyState(id,states(id).selectAll.replace(clean(id,action.value()),boundaries))
        case _ => false
