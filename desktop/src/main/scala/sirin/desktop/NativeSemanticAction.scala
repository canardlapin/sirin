package sirin.desktop

import sirin.*
import java.nio.charset.StandardCharsets

/** Additional actions in accessibility ABI v3. Focus, text and Activate retain their existing codes. */
private[desktop] object NativeSemanticAction:
  def encode(action: SemanticAction): Option[(Int, String)] = action match
    case SemanticAction.Increment => Some(5 -> "")
    case SemanticAction.Decrement => Some(6 -> "")
    case SemanticAction.Dismiss => Some(7 -> "")
    case SemanticAction.Custom(name) =>
      val bytes = name.getBytes(StandardCharsets.UTF_8)
      require(name.nonEmpty && !name.contains('\u0000') &&
        bytes.length <= 256 && new String(bytes, StandardCharsets.UTF_8) == name,
        "native custom action name budget or encoding")
      Some(8 -> name)
    case _ => None

  def declarations(actions: Set[SemanticAction]): Vector[(Int, String)] =
    val result = actions.toVector.flatMap(encode).sortBy(identity)
    require(result.size <= 32, "native semantic action budget")
    result

  def decode(kind: Int, name: String): Option[SemanticAction] = kind match
    case 5 if name.isEmpty => Some(SemanticAction.Increment)
    case 6 if name.isEmpty => Some(SemanticAction.Decrement)
    case 7 if name.isEmpty => Some(SemanticAction.Dismiss)
    case 8 if name.nonEmpty => Some(SemanticAction.Custom(name))
    case _ => None
