package sirin.desktop

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import sirin.*
import sirin.specimen.*

/** Test-only entry: deterministic slow storage around the real file repository.
  * Never included in the normal application JAR or selected by production code.
  */
object WorkbenchAcceptance:
  private final class Gates(control: Path):
    private val saves = new AtomicInteger()
    private val imports = new AtomicInteger()

    private def waitAt(kind: String, number: Int, data: DocumentData)(using cancellation: Cancellation): Unit =
      val prefix = s"$kind-$number"
      Files.writeString(control.resolve(s"$prefix.body.txt"), data.body)
      Files.writeString(control.resolve(s"$prefix.entered"), data.id.value)
      val hold = control.resolve(s"hold-$kind")
      val deadline = System.nanoTime() + 120000000000L
      try
        while Files.exists(hold) && !cancellation.cancelled && System.nanoTime() < deadline do
          Thread.sleep(10)
        if cancellation.cancelled then throw new InterruptedException("Acceptance operation cancelled")
        if Files.exists(hold) then throw new IllegalStateException("Acceptance hold timed out after 120 seconds")
        Files.writeString(control.resolve(s"$prefix.released"), "released")
      catch
        case error: InterruptedException =>
          Files.writeString(control.resolve(s"$prefix.cancelled"), "cancelled")
          throw error

    def beforeSave(data: DocumentData): Work[Unit] = Work {
      waitAt("save", saves.incrementAndGet(), data)
    }

    def afterCreate(session: DocumentSession): Work[DocumentSession] = Work {
      if session.initial.data.id.value.startsWith("live-cancel-") then
        val number = imports.incrementAndGet()
        if number == 3 then waitAt("import", number, session.initial.data)
      session
    }

  def main(args: Array[String]): Unit =
    require(args.length == 2 && args(0) == "--documents", "Acceptance entry requires --documents DIRECTORY")
    val directory = Path.of(args(1)).toAbsolutePath.normalize()
    val control = Path.of(System.getProperty("sirin.acceptance.control")).toRealPath()
    require(directory.getParent == control.getParent, "Acceptance controls and documents must share the test root")
    val files = new FileDocumentRepository(directory)
    val gates = new Gates(control)
    def wrap(session: DocumentSession): DocumentSession = new DocumentSession:
      val initial = session.initial
      def save(data: DocumentData): Work[LoadedDocument] =
        gates.beforeSave(data).flatMap(_ => session.save(data))
    val repository = new DocumentRepository:
      def list(query: String): Work[Vector[DocumentSummary]] = files.list(query)
      def open(id: DocumentId): Work[DocumentSession] = files.open(id).map(wrap)
      def create(data: DocumentData): Work[DocumentSession] =
        files.create(data).flatMap(gates.afterCreate).map(wrap)
    val importer = new FileDocumentImporter(repository)
    Application.run { (scope: Scope, services: ApplicationServices) =>
      new DocumentWorkspace(scope, repository, importer, services,
        () => DocumentId(UUID.randomUUID().toString)).windows
    }
