package sirin.host

import sirin.*

final case class EditSelection(anchor: TextPosition,head: TextPosition):
  def start: Int=math.min(anchor.utf16Offset,head.utf16Offset)
  def end: Int=math.max(anchor.utf16Offset,head.utf16Offset)
  def empty: Boolean=start==end
object EditSelection:
  def at(offset: Int): EditSelection =
    val p=TextPosition(offset,TextAffinity.Downstream);EditSelection(p,p)
final case class Preedit(text: String,selectionStart: Int,selectionEnd: Int)
final case class EditSnapshot(text: String,selection: EditSelection,preedit: Option[Preedit]=None):
  def display: String=preedit.fold(text)(p => text.take(selection.start)+p.text+text.drop(selection.end))
  def visibleSelection: EditSelection=preedit.fold(selection)(p => EditSelection(
    TextPosition(selection.start+p.selectionStart,TextAffinity.Downstream),TextPosition(selection.start+p.selectionEnd,TextAffinity.Upstream)))
  def marked: Option[(Int,Int)]=preedit.map(p => (selection.start,selection.start+p.text.length))

/** Owner snapshots only; native segmentation and retained geometry are supplied.
  * No mutation occurs until the caller publishes the returned value after UiTxn.
  */
final case class TextEditing private (snapshot: EditSnapshot,private val past: Vector[EditSnapshot],
    private val future: Vector[EditSnapshot],private val typing: Boolean):
  import TextEditing.*
  def canUndo: Boolean=past.nonEmpty
  def canRedo: Boolean=future.nonEmpty
  def historySize: Int=past.size+future.size
  def historyBytes: Long=(past++future).map(_.text.length.toLong*2).sum
  def selected: String=snapshot.text.substring(snapshot.selection.start,snapshot.selection.end)
  def cancel: TextEditing=copy(snapshot=snapshot.copy(preedit=None),typing=false)
  def sync(text: String,boundaries: String => Vector[Int]): TextEditing =
    if text==snapshot.text then this else
      validate(text)
      val stops=boundaries(text)
      val at=stops.filter(_<=math.min(text.length,snapshot.selection.head.utf16Offset)).lastOption.getOrElse(0)
      TextEditing(EditSnapshot(text,EditSelection.at(at)),Vector.empty,Vector.empty,false)
  def select(selection: EditSelection,boundaries: String => Vector[Int]): TextEditing =
    val stops=boundaries(snapshot.text)
    require(stops.contains(selection.anchor.utf16Offset) && stops.contains(selection.head.utf16Offset),"selection must use grapheme boundaries")
    cancel.copy(snapshot=snapshot.copy(selection=selection,preedit=None),typing=false)
  def selectAll: TextEditing=cancel.copy(snapshot=snapshot.copy(selection=EditSelection(
    TextPosition(0,TextAffinity.Downstream),TextPosition(snapshot.text.length,TextAffinity.Upstream)),preedit=None))
  def replace(value: String,boundaries: String => Vector[Int],coalesce: Boolean=false): TextEditing =
    val clean=normalize(value)
    val next=snapshot.text.take(snapshot.selection.start)+clean+snapshot.text.drop(snapshot.selection.end)
    validate(next)
    val raw=snapshot.selection.start+clean.length
    // Insertion can merge clusters on either side (combining marks/ZWJ).
    val at=boundaries(next).find(_>=raw).getOrElse(next.length)
    val was=snapshot.copy(preedit=None)
    val history=if coalesce && typing && snapshot.selection.empty && snapshot.preedit.isEmpty then past else trim(past:+was)
    if next==snapshot.text && snapshot.preedit.isEmpty && snapshot.selection.empty then cancel
    else TextEditing(EditSnapshot(next,EditSelection.at(at)),history,Vector.empty,coalesce)
  def mark(value: String,start: Int,end: Int): TextEditing =
    val text=normalize(value);require(text.length<=4096,"composition budget")
    require(start>=0 && start<=end && end<=text.length,"invalid composition selection")
    validate(snapshot.text.take(snapshot.selection.start)+text+snapshot.text.drop(snapshot.selection.end))
    copy(snapshot=snapshot.copy(preedit=Option.when(text.nonEmpty)(Preedit(text,start,end))),typing=false)
  def commit(value: String,boundaries: String => Vector[Int]): TextEditing=replace(value,boundaries,false)
  def undo: TextEditing =
    if snapshot.preedit.nonEmpty then cancel else past.lastOption.fold(this)(p => TextEditing(p,past.dropRight(1),trim(future:+snapshot),false))
  def redo: TextEditing = future.lastOption.fold(cancel)(p => TextEditing(p,trim(past:+snapshot.copy(preedit=None)),future.dropRight(1),false))
  def delete(backward: Boolean,word: Boolean,boundaries: String => Vector[Int],words: Vector[Int]): TextEditing =
    val base=cancel
    if !snapshot.selection.empty then base.replace("",boundaries)
    else
      val at=snapshot.selection.head.utf16Offset
      val stops=if word then words else boundaries(snapshot.text)
      val to=if backward then stops.filter(_<at).lastOption.getOrElse(0) else stops.find(_>at).getOrElse(snapshot.text.length)
      base.select(EditSelection(TextPosition(at,TextAffinity.Downstream),TextPosition(to,TextAffinity.Upstream)),boundaries).replace("",boundaries)
  def navigate(layout: TextLayout,key: Key,extend: Boolean,word: Boolean,primary: Boolean,
      boundaries: String => Vector[Int],words: Vector[Int]): TextEditing =
    require(layout.text==snapshot.text && snapshot.preedit.isEmpty,"navigation requires current presented layout")
    val from=snapshot.selection.head
    val all=boundaries(snapshot.text).flatMap(layout.caretsAt).distinct.sortBy(c => (c.lineIndex,c.bounds.origin.x.toDouble,c.position.utf16Offset,c.position.affinity.ordinal))
    val at=layout.caret(from).orElse(layout.caretsAt(from.utf16Offset).headOption).get
    val line=all.filter(_.lineIndex==at.lineIndex)
    val i=all.indexWhere(c => c.position==at.position && c.lineIndex==at.lineIndex)
    def edge(offset: Int)=layout.caretsAt(offset).headOption.getOrElse(at)
    val next=key match
      case Key.Home => if primary then edge(0) else line.head
      case Key.End => if primary then edge(snapshot.text.length) else line.last
      case Key.ArrowLeft if word => edge(words.filter(_<from.utf16Offset).lastOption.getOrElse(0))
      case Key.ArrowRight if word => edge(words.find(_>from.utf16Offset).getOrElse(snapshot.text.length))
      case Key.ArrowLeft =>
        all.take(math.max(0,i)).reverse.find(c => c.lineIndex!=at.lineIndex || c.bounds.origin.x!=at.bounds.origin.x).getOrElse(all.head)
      case Key.ArrowRight =>
        all.drop(i+1).find(c => c.lineIndex!=at.lineIndex || c.bounds.origin.x!=at.bounds.origin.x).getOrElse(all.last)
      case Key.ArrowUp | Key.ArrowDown =>
        val target=at.lineIndex+(if key==Key.ArrowUp then -1 else 1)
        all.filter(_.lineIndex==target).minByOption(c => math.abs(c.bounds.origin.x.toDouble-at.bounds.origin.x.toDouble)).getOrElse(at)
      case _ => at
    val position=if !extend && !snapshot.selection.empty && (key==Key.ArrowLeft || key==Key.ArrowRight) && !word then
      val selected=all.filter(c => c.position.utf16Offset>=snapshot.selection.start && c.position.utf16Offset<=snapshot.selection.end)
      (if key==Key.ArrowLeft then selected.head else selected.last).position
    else next.position
    select(EditSelection(if extend then snapshot.selection.anchor else position,position),boundaries)

object TextEditing:
  val MaxUnits=16384
  def normalize(value: String): String=value.replace("\r\n","\n").replace('\r','\n')
  def validate(text: String): Unit =
    require(text.length<=MaxUnits && !text.contains('\u0000'),"text field budget or NUL")
    require(text.indices.count(i => "\n\r\u000B\u000C\u0085\u2028\u2029".contains(text.charAt(i)) && !(text.charAt(i)=='\n' && i>0 && text.charAt(i-1)=='\r'))<=255,"document paragraph budget")
    var i=0
    while i<text.length do
      val c=text.charAt(i)
      if Character.isHighSurrogate(c) then
        require(i+1<text.length && Character.isLowSurrogate(text.charAt(i+1)),"unpaired surrogate");i+=2
      else {require(!Character.isLowSurrogate(c),"unpaired surrogate");i+=1}
  private def trim(history: Vector[EditSnapshot]): Vector[EditSnapshot] =
    var result=history.takeRight(64)
    while result.map(_.text.length.toLong*2).sum>2L*1024*1024 do result=result.tail
    result
  def apply(text: String): TextEditing =
    validate(text);new TextEditing(EditSnapshot(text,EditSelection.at(0)),Vector.empty,Vector.empty,false)
