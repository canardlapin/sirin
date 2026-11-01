package reviewpad

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStreamReader, OutputStreamWriter}
import java.nio.channels.FileChannel
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.Properties
import sirin.*

final case class Note(title: String, body: String, reviewed: Boolean)
object Note:
  val empty = Note("Untitled review", "", false)
  private def validText(text: String): Boolean =
    var index = 0
    while index < text.length do
      val current = text.charAt(index)
      if current == 0 || Character.isLowSurrogate(current) then return false
      if Character.isHighSurrogate(current) then
        if index + 1 >= text.length || !Character.isLowSurrogate(text.charAt(index + 1)) then return false
        index += 2
      else index += 1
    true
  def problem(note: Note): Option[String] =
    if !validText(note.title) || !validText(note.body) then Some("Use valid Unicode text without NUL characters.")
    else if note.title.trim.isEmpty then Some("Enter a title.")
    else if note.title.length > 256 || note.title.exists(_.isControl) then
      Some("Use a title of at most 256 characters.")
    else if note.body.length > 16384 then Some("Keep notes within 16,384 characters.")
    else None

final case class Loaded(note: Note, exists: Boolean)

/** Application-owned storage. Work descriptions are lazy; blocking operations run only on workers. */
final class NoteStore(path: Path):
  private val file = path.toAbsolutePath.normalize()
  private val limit = 131072
  private var expected = Option.empty[String]
  private var opened = false

  val load: Work[Loaded] = Work {
    checkCancellation()
    read()
  }
  def save(note: Note): Work[Unit] = Work {
    checkCancellation()
    write(note)
  }
  private def checkCancellation()(using cancellation: Cancellation): Unit =
    if cancellation.cancelled then throw new InterruptedException("Note operation cancelled")
  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  private def bytes(): Option[Array[Byte]] =
    try
      val input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
      try
        val data = input.readNBytes(limit + 1)
        require(data.length <= limit, "The note file is too large.")
        Some(data)
      finally input.close()
    catch case _: NoSuchFileException => None
  private def decode(data: Array[Byte]): Note =
    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    val values = new Properties
    val reader = new InputStreamReader(new ByteArrayInputStream(data), decoder)
    try values.load(reader)
    finally reader.close()
    require(values.getProperty("format") == "reviewpad-1", "Unknown note format.")
    require(Set("true", "false")(values.getProperty("reviewed")), "Invalid review state.")
    val title = Option(values.getProperty("title")).getOrElse(throw new IllegalArgumentException("Missing title."))
    val body = Option(values.getProperty("body")).getOrElse(throw new IllegalArgumentException("Missing notes."))
    val note = Note(title, body, values.getProperty("reviewed") == "true")
    require(Note.problem(note).isEmpty, "Invalid saved note.")
    note
  private def encode(note: Note): Array[Byte] =
    require(Note.problem(note).isEmpty, "Correct the note before saving.")
    val values = new Properties
    values.setProperty("format", "reviewpad-1")
    values.setProperty("title", note.title)
    values.setProperty("body", note.body)
    values.setProperty("reviewed", note.reviewed.toString)
    val output = new ByteArrayOutputStream
    val encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
    val writer = new OutputStreamWriter(output, encoder)
    values.store(writer, "ReviewPad note")
    writer.close()
    val data = output.toByteArray
    require(data.length <= limit, "The encoded note is too large.")
    data

  private[reviewpad] def read(): Loaded = synchronized {
    val current = bytes()
    val note = current.fold(Note.empty)(decode)
    expected = current.map(digest)
    opened = true
    Loaded(note, current.nonEmpty)
  }
  private[reviewpad] def write(note: Note): Unit = synchronized {
    require(opened, "Open the note before saving.")
    val data = encode(note)
    Files.createDirectories(file.getParent)
    val lockFile = file.resolveSibling(file.getFileName.toString + ".lock")
    val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS)
    try
      val lock = channel.tryLock()
      require(lock != null, "Another ReviewPad is saving this note. Try again.")
      try
        require(bytes().map(digest) == expected, "The file changed elsewhere. Reopen it before saving.")
        val temporary = Files.createTempFile(file.getParent, ".reviewpad-", ".tmp")
        try
          Files.write(temporary, data)
          Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          expected = Some(digest(data))
        finally Files.deleteIfExists(temporary)
      finally lock.release()
    finally channel.close()
  }
