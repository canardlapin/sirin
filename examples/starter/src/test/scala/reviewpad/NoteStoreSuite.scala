package reviewpad

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class NoteStoreSuite extends munit.FunSuite:
  test("review status and Unicode notes survive a fresh store, while conflicting saves retain disk bytes") {
    val directory = Files.createTempDirectory("reviewpad-test-")
    val path = directory.resolve("review.note")
    val first = new NoteStore(path)
    assertEquals(first.read(), Loaded(Note.empty, false))
    val note = Note("Résumé 日本語", "First line\nSecond line 😀", true)
    first.write(note)
    val second = new NoteStore(path)
    assertEquals(second.read(), Loaded(note, true))
    first.write(note.copy(body = "newer external save"))
    val bytes = Files.readAllBytes(path).toVector
    intercept[IllegalArgumentException](second.write(note.copy(title = "stale overwrite")))
    assertEquals(Files.readAllBytes(path).toVector, bytes)
    assertEquals(new NoteStore(path).read().note.body, "newer external save")
  }
  test("invalid and oversized files fail without overwriting or silently opening a blank note") {
    val path = Files.createTempDirectory("reviewpad-invalid-").resolve("review.note")
    Files.writeString(path, "not a review note", StandardCharsets.UTF_8)
    val store = new NoteStore(path)
    intercept[IllegalArgumentException](store.read())
    intercept[IllegalArgumentException](store.write(Note.empty))
    assertEquals(Files.readString(path), "not a review note")
    Files.write(path, Array.fill[Byte](131073)(65))
    intercept[IllegalArgumentException](new NoteStore(path).read())
    assertEquals(Files.size(path), 131073L)
  }
  test("invalid edits and malformed Unicode cannot replace a saved note") {
    val path = Files.createTempDirectory("reviewpad-validation-").resolve("review.note")
    val store = new NoteStore(path)
    store.read()
    store.write(Note.empty)
    val bytes = Files.readAllBytes(path).toVector
    intercept[IllegalArgumentException](store.write(Note("", "", false)))
    intercept[IllegalArgumentException](store.write(Note("Title", "x" * 16385, false)))
    for invalid <- Vector("\ud800", "\udc00", "\u0000", "x\ud800y") do
      for note <- Vector(Note("Title", invalid, false), Note(invalid, "Body", false)) do
        assert(Note.problem(note).nonEmpty)
        intercept[IllegalArgumentException](store.write(note))
        assertEquals(Files.readAllBytes(path).toVector, bytes)
    assertEquals(Files.readAllBytes(path).toVector, bytes)
  }
  test("escaped malformed UTF-16 and NUL in a properties file are refused before opening") {
    for escaped <- Vector("\\uD800", "\\uDC00", "\\u0000") do
      val path = Files.createTempDirectory("reviewpad-escaped-").resolve("review.note")
      val contents = s"format=reviewpad-1\ntitle=Title\nbody=$escaped\nreviewed=false\n"
      Files.writeString(path, contents, StandardCharsets.UTF_8)
      val store = new NoteStore(path)
      intercept[IllegalArgumentException](store.read())
      intercept[IllegalArgumentException](store.write(Note.empty))
      assertEquals(Files.readString(path), contents)
  }
