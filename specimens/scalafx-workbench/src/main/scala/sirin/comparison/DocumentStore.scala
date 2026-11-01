package sirin.comparison

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.{HexFormat, Locale}
import scala.util.control.NonFatal

final case class Document(id: String, title: String, body: String):
  require(Document.validId(id), "Invalid document ID")
  require(Document.problem(title, body).isEmpty, "Invalid document data")

object Document:
  def validId(id: String): Boolean = id.matches("[a-z0-9][a-z0-9-]{0,63}")

  private def unicode(text: String): Boolean =
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      if Character.isHighSurrogate(char) then
        index += 1
        if index >= text.length || !Character.isLowSurrogate(text.charAt(index)) then return false
      else if Character.isLowSurrogate(char) then return false
      index += 1
    true

  def problem(title: String, body: String): Option[String] =
    if title.trim.isEmpty then Some("A title is required.")
    else if title.length > 256 || title.exists(_.isControl) then
      Some("Use a title of at most 256 characters on one line.")
    else if body.length > 16384 then Some("This document exceeds the 16,384-character editing limit.")
    else if body.contains('\u0000') || !unicode(title) || !unicode(body) then
      Some("The document contains invalid text.")
    else None

  def message(error: Throwable): String =
    val text = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
    val clipped = text.take(512)
    if text.length > 512 && clipped.lastOption.exists(Character.isHighSurrogate) then clipped.dropRight(1)
    else clipped

final case class StoredDocument(data: Document, stamp: String)
final case class Summary(id: String, title: String, stamp: String)
final class StoreConflict extends IOException("This document changed on disk. Reload it or save a copy.")
final class StoreBusy extends IOException("This document is being saved elsewhere. Try again.")

trait StoreSession:
  def initial: StoredDocument
  def save(data: Document, cancelled: () => Boolean): StoredDocument

trait Store:
  def list(query: String, cancelled: () => Boolean): Vector[Summary]
  def open(id: String, cancelled: () => Boolean): StoreSession
  def create(data: Document, cancelled: () => Boolean): StoreSession

/** Independent synchronous JDK adapter for JavaFX Tasks. Same v1 format and
  * storage contract as Sirin's adapter; no Sirin runtime or Work dependency.
  */
final class DocumentStore(root: Path, createDirectory: Boolean = true) extends Store:
  private val configured = root.toAbsolutePath.normalize()
  private val limit = 131072
  private val extension = ".sirin-doc"

  private def check(cancelled: () => Boolean): Unit =
    if cancelled() || Thread.currentThread().isInterrupted then throw new InterruptedException("Cancelled")

  private def directory(): Path =
    if createDirectory then Files.createDirectories(configured)
    else if !Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS) then
      throw new IOException("Document directory no longer exists or is a link.")
    configured.toRealPath()

  private def path(dir: Path, id: String): Path =
    require(Document.validId(id), "Invalid document ID")
    dir.resolve(id + extension)

  private def stamp(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def encode(data: Document): Array[Byte] =
    val bytes = new ByteArrayOutputStream
    val out = new DataOutputStream(bytes)
    out.writeLong(0x534952494e444f43L)
    out.writeInt(1)
    Vector(data.id, data.title, data.body).foreach { text =>
      val value = text.getBytes(StandardCharsets.UTF_8)
      out.writeInt(value.length)
      out.write(value)
    }
    out.flush()
    val result = bytes.toByteArray
    require(result.length <= limit, "Document file budget exceeded")
    result

  private def decode(bytes: Array[Byte], expected: String): Document =
    try
      val in = new DataInputStream(new ByteArrayInputStream(bytes))
      require(in.readLong() == 0x534952494e444f43L && in.readInt() == 1, "Unknown document format")
      def string(maximum: Int): String =
        val length = in.readInt()
        require(length >= 0 && length <= maximum && length <= in.available(), "Invalid string length")
        val value = new Array[Byte](length)
        in.readFully(value)
        StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString
      val id = string(64)
      val title = string(1024)
      val body = string(65536)
      require(id == expected && in.read() == -1, "Document identity or trailing data mismatch")
      Document(id, title, body)
    catch case NonFatal(error) => throw new IOException(s"Document $expected has an invalid file format.", error)

  private def read(dir: Path, id: String, cancelled: () => Boolean): StoredDocument =
    check(cancelled)
    val file = path(dir, id)
    if !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) then
      if Files.notExists(file, LinkOption.NOFOLLOW_LINKS) then throw new NoSuchFileException(id)
      else throw new IOException("Document files must be regular files, not links.")
    val channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    val buffer = ByteBuffer.allocate(limit + 1)
    try
      var done = false
      while !done do
        check(cancelled)
        if !buffer.hasRemaining then throw new IOException("Document file budget exceeded.")
        done = channel.read(buffer) < 0
      if buffer.position() > limit then throw new IOException("Document file budget exceeded.")
      val bytes = new Array[Byte](buffer.position())
      buffer.flip()
      buffer.get(bytes)
      StoredDocument(decode(bytes, id), stamp(bytes))
    finally channel.close()

  private def current(dir: Path, id: String, cancelled: () => Boolean): Option[StoredDocument] =
    try Some(read(dir, id, cancelled))
    catch case _: NoSuchFileException => None

  private def lock[A](file: Path)(body: => A): A =
    val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    try
      val held = try channel.tryLock() catch case _: OverlappingFileLockException => throw new StoreBusy
      if held == null then throw new StoreBusy
      try body finally held.release()
    finally channel.close()

  private def documentLock[A](dir: Path, id: String)(body: => A): A =
    lock(dir.resolve(id + ".sirin-lock"))(body)

  private def files(dir: Path, cancelled: () => Boolean): Vector[String] =
    val stream = Files.newDirectoryStream(dir)
    try
      val iterator = stream.iterator()
      val result = Vector.newBuilder[String]
      var visited = 0
      var count = 0
      while iterator.hasNext do
        check(cancelled)
        val name = iterator.next().getFileName.toString
        visited += 1
        if visited > 4096 then throw new IOException("Document directory entry budget exceeded.")
        if name.endsWith(extension) then
          count += 1
          if count > 512 then throw new IOException("Document collection exceeds 512 documents.")
          result += name.stripSuffix(extension)
      result.result()
    finally stream.close()

  private def publish(dir: Path, data: Document, create: Boolean, cancelled: () => Boolean,
      beforeCommit: () => Unit = () => ()): StoredDocument =
    val bytes = encode(data)
    val temp = Files.createTempFile(dir, ".sirin-", ".pending")
    try
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
      try
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do
          check(cancelled)
          channel.write(buffer)
        channel.force(true)
      finally channel.close()
      check(cancelled)
      beforeCommit()
      if create then Files.createLink(path(dir, data.id), temp)
      else Files.move(temp, path(dir, data.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      StoredDocument(data, stamp(bytes))
    finally Files.deleteIfExists(temp)

  def list(query: String, cancelled: () => Boolean): Vector[Summary] =
    require(query.length <= 256, "Search query exceeds 256 characters")
    check(cancelled)
    val dir = directory()
    val normalized = query.toLowerCase(Locale.ROOT)
    files(dir, cancelled).map(id => read(dir, id, cancelled)).filter { stored =>
      normalized.isEmpty || stored.data.title.toLowerCase(Locale.ROOT).contains(normalized) ||
        stored.data.body.toLowerCase(Locale.ROOT).contains(normalized)
    }.map(s => Summary(s.data.id, s.data.title, s.stamp)).sortBy(_.id)

  def open(id: String, cancelled: () => Boolean): StoreSession =
    check(cancelled)
    val dir = directory()
    new Session(dir, read(dir, id, cancelled))

  def create(data: Document, cancelled: () => Boolean): StoreSession =
    check(cancelled)
    val dir = directory()
    lock(dir.resolve(".repository.lock")) {
      documentLock(dir, data.id) {
        if current(dir, data.id, cancelled).nonEmpty then throw new StoreConflict
        if files(dir, cancelled).size >= 512 then throw new IOException("Document collection is full.")
        new Session(dir, publish(dir, data, create = true, cancelled))
      }
    }

  private final class Session(dir: Path, val initial: StoredDocument) extends StoreSession:
    private var expected = initial.stamp
    def save(data: Document, cancelled: () => Boolean): StoredDocument = synchronized {
      require(data.id == initial.data.id, "A document session cannot save another document")
      check(cancelled)
      documentLock(dir, data.id) {
        def verify(): Unit =
          if !current(dir, data.id, cancelled).exists(_.stamp == expected) then throw new StoreConflict
        verify()
        val written = publish(dir, data, create = false, cancelled, () => verify())
        expected = written.stamp
        written
      }
    }
