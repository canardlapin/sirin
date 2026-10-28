package sirin

import scala.quoted.*

/** Stable logical input schema. Generic helpers carry this evidence to their
  * call sites; explicit instances must distinguish incompatible input types.
  */
final case class SchemaId[A](value: String)

object SchemaId:
  inline given derived[A]: SchemaId[A] = ${ derive[A] }
  private def derive[A: Type](using quotes: Quotes): Expr[SchemaId[A]] =
    import quotes.reflect.*
    val representation = TypeRepr.of[A].dealias
    def globallyOwned(symbol: Symbol): Boolean =
      val owner = symbol.owner
      owner.isPackageDef || (owner.flags.is(Flags.Module) && globallyOwned(owner))
    def staticPrefix(prefix: TypeRepr): Boolean = prefix match
      case NoPrefix() => true
      case term: TermRef =>
        (term.termSymbol.isPackageDef || (term.termSymbol.flags.is(Flags.Module) && globallyOwned(term.termSymbol))) && staticPrefix(term.qualifier)
      case ThisType(reference) =>
        reference.typeSymbol.isPackageDef ||
          (reference.typeSymbol.flags.is(Flags.Module) && globallyOwned(reference.typeSymbol) && (reference match
            case named: TypeRef => staticPrefix(named.qualifier)
            case _ => false))
      case reference: TypeRef =>
        !reference.typeSymbol.flags.is(Flags.Param) && !reference.typeSymbol.flags.is(Flags.Deferred) && staticPrefix(reference.qualifier)
      case _ => false
    def unresolved(tpe: TypeRepr): Boolean =
      val current = tpe.dealias
      if current.typeSymbol.flags.is(Flags.Param) || current.typeSymbol.flags.is(Flags.Deferred) then true
      else current match
        case AppliedType(constructor, arguments) => unresolved(constructor) || arguments.exists(unresolved)
        case AndType(left, right) => unresolved(left) || unresolved(right)
        case OrType(left, right) => unresolved(left) || unresolved(right)
        case AnnotatedType(underlying, _) => unresolved(underlying)
        case ByNameType(underlying) => unresolved(underlying)
        case Refinement(parent, _, info) => unresolved(parent) || unresolved(info)
        case TypeBounds(low, high) => !(low =:= high) || unresolved(high)
        case _: ParamRef | _: MatchType | _: TypeLambda => true
        case reference: TypeRef => !staticPrefix(reference.qualifier)
        case term: TermRef => !staticPrefix(term)
        case prefix: ThisType => !staticPrefix(prefix)
        case _ => false
    if unresolved(representation) then
      report.errorAndAbort("A generic structural builder must require SchemaId for its complete input and key types")
    val name = representation.show
    '{ SchemaId[A](${ Expr(name) }) }
