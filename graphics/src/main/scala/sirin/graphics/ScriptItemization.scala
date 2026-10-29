package sirin.graphics

enum BracketKind:
  case Open, Close

final case class ScriptBracket(kind: BracketKind, partner: Int)
final case class CodepointProperties(
    range: Utf8Range, codepoint: Int, primary: Script, extensions: Vector[Script],
    bracket: Option[ScriptBracket], defaultIgnorable: Boolean, variationSelector: Boolean
)
final case class ScriptItem(range: Utf8Range, script: Script, level: Int)

/** A deterministic shaping policy over supplied Unicode properties, not a
  * replacement Unicode property table. Graphemes stay intact. Common/Inherited
  * adopt compatible surrounding scripts; paired punctuation adopts its opener's
  * script. Script_Extensions constrain non-base choices. Language stays explicit.
  */
object ScriptItemization:
  def apply(analysis: UnicodeAnalysis, line: BidiLine,
      properties: Vector[CodepointProperties], maxBracketDepth: Int = 256): Vector[ScriptItem] =
    require(line.analysis eq analysis, "bidi line belongs to another analysis")
    require(line.range == Utf8Range(Utf8Offset(0), Utf8Offset(analysis.source.byteSize)),
      "this itemizer requires a complete single paragraph")
    require(maxBracketDepth > 0, "bracket budget must be positive")
    require(properties.map(_.range) == line.codepoints.map(_.range), "properties must cover source codepoints")
    val levels = line.codepoints.map(p => p.level.getOrElse(analysis.bidiLevels(p.range.start.value)))
    val groups = Vector.newBuilder[(Utf8Range, Vector[CodepointProperties], Int)]
    var cp = 0
    analysis.graphemeBoundaries.zip(analysis.graphemeBoundaries.drop(1)).foreach { (start, end) =>
      val first = cp
      while cp < properties.size && properties(cp).range.start.value < end.value do cp += 1
      val visibleLevels = line.codepoints.slice(first, cp).flatMap(_.level).distinct
      require(visibleLevels.size <= 1, "bidi level boundary inside a grapheme is unsupported")
      groups += ((Utf8Range(start, end), properties.slice(first, cp), visibleLevels.headOption.getOrElse(levels(first))))
    }
    val tokens = groups.result()
    def explicit(script: Script): Boolean = script != Script.Common && script != Script.Inherited
    def choices(values: Vector[CodepointProperties]): Set[Script] =
      // A grapheme's base script wins over inherited marks with narrower SCX.
      values.find(p => explicit(p.primary) && p.primary != Script.Unknown) match
        case Some(base) => Set(base.primary)
        case None =>
          val sets = values.map(_.extensions.filter(explicit).toSet).filter(_.nonEmpty)
          if sets.isEmpty then Set.empty
          else sets.reduce { (a, b) => val intersection = a.intersect(b); if intersection.nonEmpty then intersection else a }
    val candidates = tokens.map(t => choices(t._2))
    val right = new Array[Option[Script]](tokens.size)
    var nearest: Option[Script] = None
    var i = tokens.size - 1
    while i >= 0 do
      right(i) = nearest
      if candidates(i).nonEmpty then nearest = Some(candidates(i).toVector.sortBy(_.tag).head)
      i -= 1
    val result = scala.collection.mutable.ArrayBuffer.empty[ScriptItem]
    val brackets = scala.collection.mutable.ArrayBuffer.empty[(Int, Script)]
    var previous: Option[Script] = None
    tokens.indices.foreach { index =>
      val (range, values, level) = tokens(index)
      val allowed = candidates(index)
      def compatible(script: Script): Boolean = allowed.isEmpty || allowed.contains(script)
      var script = previous.filter(compatible).orElse(right(index).filter(compatible))
        .orElse(allowed.toVector.sortBy(_.tag).headOption).getOrElse(Script.Common)
      values.head.bracket.foreach { bracket =>
        bracket.kind match
          case BracketKind.Open =>
            require(brackets.size < maxBracketDepth, "script bracket nesting exceeds budget")
            brackets += ((bracket.partner, script))
          case BracketKind.Close =>
            val matchIndex = brackets.lastIndexWhere(_._1 == values.head.codepoint)
            if matchIndex >= 0 then
              if compatible(brackets(matchIndex)._2) then script = brackets(matchIndex)._2
              brackets.remove(matchIndex, brackets.size - matchIndex)
      }
      previous = Some(script)
      if result.nonEmpty && result.last.script == script && result.last.level == level then
        result(result.size - 1) = result.last.copy(range = Utf8Range(result.last.range.start, range.end))
      else result += ScriptItem(range, script, level)
    }
    result.toVector
