package sirin.comparison

import java.io.{ByteArrayOutputStream, DataOutputStream, IOException}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat

class DocumentStoreSuite extends munit.FunSuite:
  private val active = () => false
  private def directory(): Path = Files.createTempDirectory("scalafx-store-")
  private val data = Document("first", "First", "Body\nwith 日本語 and 👩🏽‍💻")
  private def interrupted(action: => Unit): Unit =
    var caught = false
    try action catch case _: InterruptedException => caught = true
    assert(caught, "operation must throw InterruptedException")

  test("read the exact Sirin-created acceptance fixture and write identical bytes") {
    val stream = getClass.getResourceAsStream("/acceptance-alpha.sirin-doc")
    val bytes = try stream.readAllBytes() finally stream.close()
    val expected = "1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28"
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), expected)
    val dir = directory()
    val id = "3a07f5e5-a5e1-476e-8f18-a6d37a5d6075"
    Files.write(dir.resolve(id + ".sirin-doc"), bytes)
    val loaded = new DocumentStore(dir).open(id, active).initial
    assertEquals(loaded.data.title, "Acceptance Alpha")
    assertEquals(loaded.data.body, "Workbench acceptance: saved body 2026-10-31.")
    val output = directory()
    new DocumentStore(output).create(loaded.data, active)
    assertEquals(Files.readAllBytes(output.resolve(id + ".sirin-doc")).toVector, bytes.toVector)
  }

  test("queued writes share their own session stamp; another writer is refused without overwrite") {
    val dir = directory()
    val store = new DocumentStore(dir)
    val first = store.create(data, active)
    val external = store.open(data.id, active)
    first.save(data.copy(body = "revision 17"), active)
    first.save(data.copy(body = "revision 18"), active)
    intercept[StoreConflict](external.save(data.copy(body = "stale"), active))
    assertEquals(store.open(data.id, active).initial.data.body, "revision 18")
    intercept[StoreConflict](store.create(data.copy(body = "replacement"), active))
    assertEquals(store.open(data.id, active).initial.data.body, "revision 18")
  }

  test("an external replacement and deletion both refuse the old session") {
    val dir = directory()
    val store = new DocumentStore(dir)
    val stale = store.create(data, active)
    store.open(data.id, active).save(data.copy(title = "External"), active)
    val file = dir.resolve(data.id + ".sirin-doc")
    val external = Files.readAllBytes(file).toVector
    intercept[StoreConflict](stale.save(data.copy(body = "draft"), active))
    assertEquals(Files.readAllBytes(file).toVector, external)
    Files.delete(file)
    intercept[StoreConflict](stale.save(data, active))
    assert(!Files.exists(file))
  }

  test("strict UTF-8, identity, trailing bytes and links are refused") {
    val dir = directory()
    val store = new DocumentStore(dir)
    store.create(data, active)
    val file = dir.resolve("first.sirin-doc")
    val valid = Files.readAllBytes(file)
    Files.write(file, valid ++ Array[Byte](1))
    intercept[IOException](store.open("first", active))
    Files.write(file, valid)
    Files.copy(file, dir.resolve("wrong.sirin-doc"))
    intercept[IOException](store.open("wrong", active))
    val bytes = new ByteArrayOutputStream
    val out = new DataOutputStream(bytes)
    out.writeLong(0x534952494e444f43L)
    out.writeInt(1)
    Vector("first".getBytes("UTF-8"), Array[Byte](-61, 40), Array.emptyByteArray).foreach { value =>
      out.writeInt(value.length)
      out.write(value)
    }
    Files.write(file, bytes.toByteArray)
    intercept[IOException](store.open("first", active))
    Files.delete(file)
    Files.createSymbolicLink(file, dir.resolve("wrong.sirin-doc"))
    intercept[IOException](store.open("first", active))
  }

  test("cancelled create and save preserve existing storage") {
    val dir = directory()
    val store = new DocumentStore(dir)
    interrupted(store.create(data, () => true))
    assert(!Files.exists(dir.resolve("first.sirin-doc")))
    val session = store.create(data, active)
    val before = Files.readAllBytes(dir.resolve("first.sirin-doc")).toVector
    interrupted(session.save(data.copy(body = "changed"), () => true))
    assertEquals(Files.readAllBytes(dir.resolve("first.sirin-doc")).toVector, before)
  }

  test("validation matches document bounds and rejects malformed UTF-16 before encoding") {
    assert(Document.problem("", "").nonEmpty)
    assert(Document.problem("a" * 257, "").nonEmpty)
    assert(Document.problem("Title", "a" * 16385).nonEmpty)
    assert(Document.problem("Title\n", "").nonEmpty)
    assert(Document.problem("Title", "\u0000").nonEmpty)
    assert(Document.problem("\ud800", "").nonEmpty)
    assert(Document.problem("Title", "\udc00").nonEmpty)
    assertEquals(Document.problem("a" * 256, "b" * 16384), None)
  }
