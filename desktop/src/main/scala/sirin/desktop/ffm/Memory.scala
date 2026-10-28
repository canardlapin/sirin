package sirin.desktop.ffm

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import scala.jdk.CollectionConverters.*

/** Private platform adaptation for the existing pointer-only font/Unicode ABI.
  * Every allocation has an explicit arena; result reads use native validated counts.
  */
object Memory:
  type CLongLong = Long
  type CInt = Int
  type CUnsignedInt = Int
  type CDouble = Double
  type CFloat = Float
  type CString = Ptr[Byte]
  extension (value: Int) def toUInt: Int = value
  trait Layout[A]:
    def size: Long
    def read(segment: MemorySegment, offset: Long): A
    def write(segment: MemorySegment, offset: Long, value: A): Unit
  given Layout[Byte] with
    def size = 1L
    def read(s: MemorySegment, o: Long) = s.get(ValueLayout.JAVA_BYTE, o)
    def write(s: MemorySegment, o: Long, v: Byte) = s.set(ValueLayout.JAVA_BYTE, o, v)
  given Layout[Int] with
    def size = 4L
    def read(s: MemorySegment, o: Long) = s.get(ValueLayout.JAVA_INT, o)
    def write(s: MemorySegment, o: Long, v: Int) = s.set(ValueLayout.JAVA_INT, o, v)
  given Layout[Double] with
    def size = 8L
    def read(s: MemorySegment, o: Long) = s.get(ValueLayout.JAVA_DOUBLE, o)
    def write(s: MemorySegment, o: Long, v: Double) = s.set(ValueLayout.JAVA_DOUBLE, o, v)
  given Layout[Float] with
    def size = 4L
    def read(s: MemorySegment, o: Long) = s.get(ValueLayout.JAVA_FLOAT, o)
    def write(s: MemorySegment, o: Long, v: Float) = s.set(ValueLayout.JAVA_FLOAT, o, v)
  given [A: Layout]: Layout[Ptr[A]] with
    def size = ValueLayout.ADDRESS.byteSize()
    def read(s: MemorySegment, o: Long) = pointer[A](s.get(ValueLayout.ADDRESS, o))
    def write(s: MemorySegment, o: Long, v: Ptr[A]) = s.set(ValueLayout.ADDRESS, o, if v == null then MemorySegment.NULL else v.raw)
  final class Ptr[A](val raw: MemorySegment)(using layout: Layout[A]):
    def apply(index: Int): A =
      require(index >= 0, "negative native index")
      layout.read(raw.reinterpret(Math.multiplyExact(index.toLong + 1, layout.size)), index.toLong * layout.size)
    def update(index: Int, value: A): Unit =
      require(index >= 0, "negative native index")
      layout.write(raw.reinterpret(Math.multiplyExact(index.toLong + 1, layout.size)), index.toLong * layout.size, value)
    def unary_! : A = apply(0)
  def pointer[A: Layout](raw: MemorySegment): Ptr[A] = if raw.address() == 0 then null else new Ptr[A](raw)
  object Zone:
    def acquire[A](run: Arena => A): A =
      val arena = Arena.ofConfined()
      try run(arena) finally arena.close()
  def alloc[A: Layout](count: Int = 1)(using arena: Arena): Ptr[A] =
    require(count > 0, "native allocation must be positive")
    val layout = summon[Layout[A]]
    pointer[A](arena.allocate(Math.multiplyExact(count.toLong, layout.size), layout.size))
  def toCString(value: String)(using arena: Arena): CString = pointer[Byte](arena.allocateFrom(value))
  def fromCString(value: CString): String =
    require(value != null, "null native string")
    value.raw.reinterpret(1048576).getString(0)

object Calls:
  import Memory.*
  private val arena = Arena.ofShared()
  private val library = SymbolLookup.libraryLookup(java.nio.file.Path.of(System.getProperty("sirin.desktop.library")), arena)
  private val linker = Linker.nativeLinker()
  private val handles = scala.collection.mutable.Map.empty[(String, Option[MemoryLayout], Vector[MemoryLayout]), MethodHandle]
  private def layout(value: Any): MemoryLayout = value match
    case null => ValueLayout.ADDRESS
    case _: Ptr[?] => ValueLayout.ADDRESS
    case _: Int => ValueLayout.JAVA_INT
    case _: Double => ValueLayout.JAVA_DOUBLE
    case _: Float => ValueLayout.JAVA_FLOAT
    case _ => throw new IllegalArgumentException("unsupported private FFM argument")
  private def call(name: String, result: Option[MemoryLayout], args: Seq[Any]): AnyRef =
    val types = args.map(layout).toVector
    val handle = handles.synchronized { handles.getOrElseUpdate((name, result, types),
      linker.downcallHandle(library.find(name).orElseThrow(), result.fold(FunctionDescriptor.ofVoid(types*))(r => FunctionDescriptor.of(r, types*)))) }
    handle.invokeWithArguments(args.map {
      case null => MemorySegment.NULL
      case p: Ptr[?] => p.raw: AnyRef
      case other => other.asInstanceOf[AnyRef]
    }.asJava)
  def int(name: String, args: Any*): Int = call(name, Some(ValueLayout.JAVA_INT), args).asInstanceOf[Integer].intValue()
  def long(name: String, args: Any*): Long = call(name, Some(ValueLayout.JAVA_LONG), args).asInstanceOf[java.lang.Long].longValue()
  def ptr[A: Layout](name: String, args: Any*): Ptr[A] = pointer[A](call(name, Some(ValueLayout.ADDRESS), args).asInstanceOf[MemorySegment])
  def unit(name: String, args: Any*): Unit = { call(name, None, args); () }
